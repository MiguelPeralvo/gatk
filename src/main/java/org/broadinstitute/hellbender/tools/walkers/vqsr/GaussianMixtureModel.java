package org.broadinstitute.hellbender.tools.walkers.vqsr;

import com.google.common.annotations.VisibleForTesting;
import joptsimple.internal.Strings;
import org.apache.commons.math3.linear.*;
import org.apache.commons.math3.ml.clustering.CentroidCluster;
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer;
import org.apache.commons.math3.ml.distance.EuclideanDistance;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.broadinstitute.hellbender.utils.Utils;

import java.util.*;

import static java.lang.Double.isInfinite;
import static java.lang.Math.*;
import static org.apache.commons.math3.special.Gamma.digamma;
import static org.broadinstitute.hellbender.utils.MathUtils.*;
import static org.broadinstitute.hellbender.utils.Utils.getRandomGenerator;

/**
 * Defines functionality for fitting gaussian mixture models to data and computing likelihood of data points.
 * This class implements the Variational Bayes Expectation Maximization algorithm to fit the model to the data.
 *
 * The implementation follows the textbook, using the same names for parameters whenever possible:
 * Kevin P. Murphy "Machine Learning: A probabilistic perspective"
 * Equation numbers correspond to the first edition, first printing.
 *
 * Note: certain parts of the implementation require a random number generator.
 * Right now, this class uses the generator from {@link Utils#apacheRandomGenerator}.
 */
final class GaussianMixtureModel {

    public final static double MIN_ACCEPTABLE_LOD_SCORE = -20000.0;
    public final static double MIN_PROB_CONVERGENCE = 2E-3;

    protected final static Logger logger = LogManager.getLogger(GaussianMixtureModel.class);

    //The gaussians we're fitting. Order is undefined.
    private final Collection<MultivariateGaussian> gaussians;

    private boolean isModelReadyForEvaluation;    //set to true after the EM iterations converge and the precomputeDenominatorForEvaluation method is called
    private boolean failedToConverge;             //set to true if something went wrong in the fitting

    private GaussianMixtureModel(final int numGaussians, final int numAnnotations,
                                                  final double prior_alpha, final double prior_beta, final double prior_nu) {
        this.gaussians = new HashSet<>( numGaussians );
        final RealVector prior_m = new ArrayRealVector(numAnnotations, 0.0);             //prior mean is the zero vector
        final RealMatrix prior_L = identityMatrix(numAnnotations).scalarMultiply(200.0); //prior precision is diagonal 200.0

        for( int i = 0; i < numGaussians; i++ ) {
            MultivariateGaussian g = new MultivariateGaussian( numAnnotations, prior_alpha, prior_beta, prior_nu, prior_m, prior_L);
            gaussians.add( g );
        }

        this.isModelReadyForEvaluation = false;
        this.failedToConverge = false;
    }

    /**
     * Makes an empty gaussian mixture model.
     * This is useful mostly in testing, where we want to set the gaussians rather than infer them.
     */
    public static GaussianMixtureModel makeEmptyModel(final int numGaussians, final int numAnnotations,
                                                      final double prior_alpha, final double prior_beta, final double prior_nu) {
        return new GaussianMixtureModel(numGaussians, numAnnotations, prior_alpha, prior_beta, prior_nu);
    }

    /**
     * Fits a model to the provided data and returns a fitted model.
     */
    public static GaussianMixtureModel makeFittedModel( final List<VariantDatum> data, final int maxGaussians, final VariantRecalibratorArgumentCollection VRAC) {
        if( data == null || data.isEmpty() ) { throw new IllegalArgumentException("No data found."); }
        if( maxGaussians <= 0 ) { throw new IllegalArgumentException("maxGaussians must be a positive integer but found: " + maxGaussians); }

        final GaussianMixtureModel model = GaussianMixtureModel.makeEmptyModel(maxGaussians, data.get(0).annotations.getDimension(), VRAC.DIRICHLET_PARAMETER, VRAC.SHRINKAGE, VRAC.PRIOR_COUNTS );
        model.initializeRandomModel(data, VRAC.NUM_KMEANS_ITERATIONS, Utils.getApacheRandomGenerator());
        model.variationalBayesExpectationMaximization(data, VRAC.MAX_ITERATIONS, MIN_PROB_CONVERGENCE);
        return model;
    }

    /**
     * Sets the gaussians to the arguments (removes the current one).
     * Note that the actual data is not copied, just set.
     * Strictly for testing only.
     */
    @VisibleForTesting
    void setGaussians(Collection<MultivariateGaussian> mvgs) {
        gaussians.clear();
        gaussians.addAll(mvgs);
    }

    /**
     * Returns the gaussians.
     * Use with caution - the live objects are returned and must not be modified.
     * Only for testing.
     */
    @VisibleForTesting
    Collection<MultivariateGaussian> getGaussians() {
        return gaussians;
    }

    /**
     * Fits the Gaussian Mixture model to the data.
     * The model is assumed to have been initialized (eg by k-means).
     */
    private void variationalBayesExpectationMaximization( final List<VariantDatum> data, int maxIterations, double minProbConvergence ) {
        // The VBEM loop
        normalizePMixtureLog10(data.size());
        expectationStep(data);
        double currentChangeInMixtureCoefficients;
        int iteration = 0;
        logger.info("Finished iteration " + iteration + ".");
        while( iteration < maxIterations ) {
            iteration++;
            maximizationStep(data);
            currentChangeInMixtureCoefficients = normalizePMixtureLog10(data.size());
            expectationStep(data);
            if( iteration % 5 == 0 ) { // cut down on the number of output lines so that users can read the warning messages
                logger.info("Finished iteration " + iteration + ". \tCurrent change in mixture coefficients = " + String.format("%.5f", currentChangeInMixtureCoefficients));
            }
            if( iteration > 2 && currentChangeInMixtureCoefficients < minProbConvergence ) {
                logger.info("Convergence after " + iteration + " iterations!");
                break;
            }
        }

        evaluateFinalModelParameters(data);
    }

    /**
     * Initializes the model by running k-means on the data. K is the same as the number of gaussians.
     */
    private void initializeRandomModel( final List<VariantDatum> data, final int numKMeansIterations, RandomGenerator rand) {

        gaussians.forEach(g -> g.initializeRandomXBar(rand)); //XXX: this should be removable but a test fails

        logger.info("Initializing model with " + numKMeansIterations + " k-means iterations...");
        initializeMeansUsingKMeans(data, numKMeansIterations, rand);

        // initialize uniform mixture coefficients, random covariance matrices, and initial hyperparameters
        for( final MultivariateGaussian gaussian : gaussians) {
            gaussian.setpMixtureLog10(log10(1.0 / ((double) gaussians.size())));
            gaussian.setParam_N((1.0 * data.size()) / ((double) gaussians.size()));
            gaussian.initializeRandomS(rand);
        }
    }

    /*
     * Performs k-means clustering for a fixed number of iterations to initialize the gaussian means.
     */
    private void initializeMeansUsingKMeans(final List<VariantDatum> data, final int numIterations, final RandomGenerator rand) {
        final KMeansPlusPlusClusterer<VariantDatum> kmeans = new KMeansPlusPlusClusterer<>(gaussians.size(), numIterations, new EuclideanDistance(), rand);
        final List<CentroidCluster<VariantDatum>> clusters = kmeans.cluster(data);

        final MultivariateGaussian[] aArr = this.gaussians.toArray(new MultivariateGaussian[this.gaussians.size()]);
        for (int i = 0; i < aArr.length; i++) {
            aArr[i].setParam_XBar(new ArrayRealVector(clusters.get(i).getCenter().getPoint()));
        }
    }

    private void expectationStep( final List<VariantDatum> data ) {
        final double sumOfAlphas = gaussians.stream().mapToDouble(MultivariateGaussian::getParam_alpha).sum();
        gaussians.forEach(g -> g.precomputeDenominatorForVariationalBayes(sumOfAlphas));

        //This loop recomputes the partial responsibilities of each gaussian for each data item
        //it implements eq. 21.133 from Murphy
        for( final VariantDatum datum : data ) {
            //first get the values for each gaussian
            final double[] valueLog10 = new double[gaussians.size()];
            int gaussianIndex1 = 0;
            for( final MultivariateGaussian gaussian : gaussians ) {
                valueLog10[gaussianIndex1++] = gaussian.evaluateDatumLog10(datum);
            }
            //second normalize them to sum to 1
            final double[] pVarInGaussianNormalized = normalizeFromLog10(valueLog10);
            int gaussianIndex2 = 0;
            for( final MultivariateGaussian gaussian : gaussians ) {
                gaussian.assignPVarInGaussian( pVarInGaussianNormalized[gaussianIndex2++] );
            }
        }
    }

    private void maximizationStep( final List<VariantDatum> data ) {
        gaussians.forEach(g -> g.maximizeGaussian( data ));
    }

    private void evaluateFinalModelParameters( final List<VariantDatum> data ) {
        gaussians.forEach(g -> g.evaluateFinalModelParameters(data));
        normalizePMixtureLog10(data.size());
    }

    private double normalizePMixtureLog10(int dataSize) {
        final double log10SumPK = log10(dataSize);

        int gaussianIndex1 = 0;
        final double[] pGaussianLog10 = new double[gaussians.size()];
        for( final MultivariateGaussian gaussian : gaussians ) {
            pGaussianLog10[gaussianIndex1++] = log10(gaussian.getParam_N()) - log10SumPK;
        }
        final double[] pGaussianLog10Normalized = normalizeFromLog10(pGaussianLog10, true);

        double sumDiff = 0.0;
        int gaussianIndex2 = 0;
        for( final MultivariateGaussian gaussian : gaussians ) {
            sumDiff += Math.abs(pGaussianLog10Normalized[gaussianIndex2] - gaussian.getpMixtureLog10());
            gaussian.setpMixtureLog10(pGaussianLog10Normalized[gaussianIndex2++]);
        }
        return sumDiff;
    }

    /**
     * Before data is evaluated, this methods needs to be called to precompute some numbers that are used for all values.
     */
    private void precomputeDenominatorForEvaluation() {
        gaussians.forEach(MultivariateGaussian::precomputeDenominatorForEvaluation);

        isModelReadyForEvaluation = true;
    }

    /**
     *  Return the likelihood of the data under the current model.
     *  If any dimension has isNull set to true, the missing dimensions will be marginalized
     *  over (by using random sampling).
     */
    public double evaluateDatum( final VariantDatum datum ) {
        if( !readyForEvaluation() ) {
            precomputeDenominatorForEvaluation();
        }

        for( final boolean isNull : datum.isNull ) {
            if( isNull ) {
                return evaluateDatumMarginalized( datum );
            }
        }
        // Fill an array with the log10 probability coming from each Gaussian and then use MathUtils to sum them up correctly
        final double[] pVarInGaussianLog10 = new double[gaussians.size()];
        int gaussianIndex = 0;
        for( final MultivariateGaussian gaussian : gaussians ) {
            pVarInGaussianLog10[gaussianIndex++] = gaussian.getpMixtureLog10() + gaussian.evaluateDatumLog10(datum);
        }
        final double result = nanTolerantLog10SumLog10(pVarInGaussianLog10); // Sum(pi_k * p(v|n,k))
        if (Double.isNaN(result)){
            failedToConverge = true;
        }
        return result;
    }

    /**
     * Return the likelihood of the data under the current model but using only 1 dimension of the gaussians.
     * Used only to decide which covariate dimension is most divergent in order to report in the culprit info field annotation
     */
    public Double evaluateDatumInOneDimension( final VariantDatum datum, final int i ) {
        if( !readyForEvaluation() ) {
            precomputeDenominatorForEvaluation();
        }

        if(datum.isNull[i]) {
            return null;
        }

        final double[] pVarInGaussianLog10 = new double[gaussians.size()];
        int gaussianIndex = 0;
        for( final MultivariateGaussian gaussian : gaussians ) {
            pVarInGaussianLog10[gaussianIndex++] = gaussian.getpMixtureLog10() + gaussian.evaluateDatumInOneDimension(datum, i);
        }
        return nanTolerantLog10SumLog10(pVarInGaussianLog10); // Sum(pi_k * p(v|n,k))
    }

    private double evaluateDatumMarginalized( final VariantDatum datum ) {
        int numRandomDraws = 0;
        double sumPVarInGaussian = 0.0;
        final int numIterPerMissingAnnotation = 1000; // Trade off here between speed of computation and accuracy of the marginalization
        final double[] pVarInGaussianLog10 = new double[gaussians.size()];
        // for each dimension
        for( int i = 0; i < datum.annotations.getDimension(); i++ ) {
            // if it is missing then marginalize over the missing dimension by drawing X random values for the missing annotation and averaging the lod
            if( datum.isNull[i] ) {
                for( int j = 0; j < numIterPerMissingAnnotation; j++ ) {
                    datum.annotations.setEntry(i, Utils.getRandomGenerator().nextGaussian()); // draw a random sample from the standard normal distribution

                    // evaluate this random data point
                    int gaussianIndex = 0;
                    for( final MultivariateGaussian gaussian : gaussians ) {
                        pVarInGaussianLog10[gaussianIndex++] = gaussian.getpMixtureLog10() + gaussian.evaluateDatumLog10(datum);
                    }

                    // add this sample's probability to the pile in order to take an average in the end
                    sumPVarInGaussian += pow(10.0, nanTolerantLog10SumLog10(pVarInGaussianLog10)); // p = 10 ^ Sum(pi_k * p(v|n,k))
                    numRandomDraws++;
                }
            }
        }
        if (numRandomDraws == 0){
            failedToConverge = true;
        }
        return log10(sumPVarInGaussian / ((double) numRandomDraws));
    }

    @Override
    public String toString() {
        List<String> sb = new ArrayList<>();
        sb.add("numGaussians:" +  gaussians.size());
        for (MultivariateGaussian mvg: gaussians){
            sb.add("------------");
            sb.add(mvg.toString());
        }
        return Utils.join("\n", sb);
    }

    /**
     * Returns whether the model has been fit and it ready for evaluation.
     * You need to call precomputeDenominatorForEvaluation to make it so.
     */
    public boolean readyForEvaluation() {
        return isModelReadyForEvaluation;
    }

    /**
     * Returns whether the model fitting failed to converge.
     */
    public boolean failedToConverge() {
        return failedToConverge;
    }

    /**
     * Computes the lod score for each data item and assigns it to the item.
     * TODO: refactor this - setting fields like this is not great.
     */
    public void setLodFromModel( final List<VariantDatum> data, final boolean isPositiveModel) {
        if( !readyForEvaluation() ) {
            precomputeDenominatorForEvaluation();
        }
        if (failedToConverge()){
            return;
        }

        logger.info("Evaluating full set of " + data.size() + " variants on positive model...");
        for( final VariantDatum datum : data ) {
            datum.lod = computeLod(datum, isPositiveModel);
            if( failedToConverge() ) {
                return;
            }
        }
    }

    private double computeLod(VariantDatum datum, boolean isPositiveModel){
        final double thisLod = evaluateDatum(datum);
        if (isPositiveModel){
            return thisLod; // positive model only so set the lod and return
        }

        final double positiveLod = datum.lod;
        final double negativeLod = thisLod;
        if (isInfinite(positiveLod)) {
            return (MIN_ACCEPTABLE_LOD_SCORE + getRandomGenerator().nextDouble() * MIN_ACCEPTABLE_LOD_SCORE); // Negative infinity lod values are possible when covariates are extremely far away from their tight Gaussians
        }

        return datum.prior + positiveLod - negativeLod;
    }

    /**
     * This class represents one gaussian in the mixture model.
     */
    @VisibleForTesting
    static final class MultivariateGaussian {
        public static final double MU_MIN = -4.0;
        public static final double MU_MAX = 4.0;
        public static final double MU_SPAN = MU_MAX - MU_MIN;

        private final int dim;            //number of dimensions of this gaussian
        private final double prior_nu;    //prior counts
        private final double prior_beta;  //shrinkage
        private final double prior_alpha; //dirichlet parameter
        private final RealVector prior_m;  //prior on the mean vector. Size = dim
        private final RealMatrix prior_L;  //prior on the inverse variance matrix. Size = dim x dim

        //Parameters fitted by the model. They corresponds to parameters indexed by subscript k in Murphy chapter 21.6.1.3
        private double param_N;                //sum of the fractional weigths assigned to this gaussian from all the data points
        private RealVector param_xbar;         //mean vector
        private RealVector param_m;
        private RealMatrix param_L;            //precision matrix
        private RealMatrix param_S;            //covariance matrix
        private double param_nu;
        private double param_beta;
        private double param_alpha;
        private final List<Double> pVarInGaussian;

        private double pMixtureLog10;        //log10 of the mixture weight for this gaussian

        //caches
        private double cachedDenomLog10;

        MultivariateGaussian(int numDimensions, double prior_alpha, double prior_beta, double prior_nu, RealVector prior_m, RealMatrix prior_L) {
            this.dim = numDimensions;
            this.prior_alpha = prior_alpha;
            this.prior_beta = prior_beta;
            this.prior_nu = prior_nu;
            this.prior_m = prior_m;
            this.prior_L = prior_L;

            this.param_xbar = new ArrayRealVector(numDimensions); //empty
            this.param_S = new Array2DRowRealMatrix(numDimensions,numDimensions);   //empty
            this.param_L = new Array2DRowRealMatrix(numDimensions,numDimensions);   //empty
            this.param_alpha = prior_alpha;
            this.param_beta = prior_beta;
            this.param_nu = prior_nu;
            this.param_m = prior_m;
            this.pVarInGaussian = new ArrayList<>();
        }

        @Override
        public String toString() {
            List<String> sb = new ArrayList<>();
            sb.add("pMixtureLog10:" + pMixtureLog10);
            sb.add("param_N:" + param_N);
            sb.add("mu:" + param_xbar.toString());
            sb.add("param_nu:" + param_nu);
            sb.add("param_beta:" + param_beta);
            sb.add("param_alpha:" + param_alpha);
            sb.add("cachedDenomLog10:" + cachedDenomLog10);
            sb.add("pVarInGaussian:" + pVarInGaussian);

            return Strings.join(sb, "\n");
        }

        void initializeRandomXBar(final RandomGenerator rand) {
            for (int i = 0; i < dim; i++) {
                param_xbar.setEntry(i, MU_MIN + MU_SPAN * rand.nextDouble());
            }
        }

        void initializeRandomS(final RandomGenerator rand) {
            //This is equiv to drawing from Wishart.
            //Note: maybe we want empirical cov from kmeans clusters
            final double[][] randSigma = new double[dim][dim];
            for (int i = 0; i < dim; i++) {
                for (int j = i; j < dim; j++) {
                    randSigma[j][i] = 0.55 + 1.25 * rand.nextDouble();
                    if (rand.nextBoolean()) {
                        randSigma[j][i] *= -1.0;
                    }
                }
            }
            // Sigma is a symmetric, positive-definite matrix created by taking a lower triangular
            // matrix and multiplying it by its transpose

            RealMatrix tmp = new Array2DRowRealMatrix(randSigma);
            setParam_S(tmp.multiply(tmp.transpose()));
        }

        static RealMatrix inverse(RealMatrix m) {
            return new LUDecomposition(m).getSolver().getInverse();
        }

        private static double determinant(RealMatrix m) {
            return new LUDecomposition(m).getDeterminant();
        }

        void evaluateFinalModelParameters(final List<VariantDatum> data) {
            recompute_paramN(data);    //equation 21.140 from Murphy
            recompute_paramXBar(data);     //equation 21.146 from Murphy
            recompute_paramS(data);  //equation 21.147 from Murphy (without the division by param_N at the end)
            resetPVarInGaussian(); // clean up some memory
        }

        void precomputeDenominatorForEvaluation() {
            //HACK: need this dance because the final evaluation step
            //needs to be independent of param_nu but the eval function mutliplies by it.
            //So we divide by it here. But for the determimant, we use the real matrix.
            final double det = 1.0 / determinant(param_L);
            cachedDenomLog10 = log10(pow(2.0 * Math.PI, -1.0 * ((double) dim) / 2.0)) + log10(pow(det, -0.5));
            param_L = param_L.scalarMultiply(1.0 / param_nu);
        }

        void precomputeDenominatorForVariationalBayes(final double sumHyperParameterAlpha) {

            //Murphy eq. 21.129
            final double logOfPiTilde = digamma(param_alpha) - digamma(sumHyperParameterAlpha);

            //Murphy eq. 21.131
            double logOfLambdaTilde = 0.0;
            for (int j = 1; j <= dim; j++) {    //note: using j to conform with Murphy
                logOfLambdaTilde += digamma((param_nu + 1.0 - j) / 2.0);
            }
            logOfLambdaTilde += dim * log(2.0);
            logOfLambdaTilde += log(determinant(param_L));

            //Murphy eq. 21.133 (multiplicative factor that does not depend on i -- index over data).
            // The second part of this equation is in evaluateDatumLog10
            cachedDenomLog10 = lnToLog10(logOfPiTilde + (0.5 * logOfLambdaTilde) + ((-1.0 * dim) / (2.0 * param_beta)));
        }

        private static double lnToLog10(final double x){
            return x / NATURAL_LOG_OF_TEN;
        }

        double evaluateDatumLog10(final VariantDatum datum) {
            //This is the rest of Murphy eq. 21.133 <- the part that is dependent on the data
            final RealVector dataMinusMu = datum.annotations.subtract(param_m);
            final double sumKernel = param_L.preMultiply(dataMinusMu).dotProduct(dataMinusMu);
            return lnToLog10(-0.5 * param_nu * sumKernel) + cachedDenomLog10;
        }

        public double evaluateDatumInOneDimension(VariantDatum datum, int i) {
            return normalDistributionLog10(param_xbar.getEntry(i), param_S.getEntry(i, i), datum.annotations.getEntry(i));
        }

        void assignPVarInGaussian(final double pVar) {
            pVarInGaussian.add(pVar);
        }

        void resetPVarInGaussian() {
            pVarInGaussian.clear();
        }

        void maximizeGaussian(final List<VariantDatum> data) {
            recompute_paramN(data);    //equation 21.140 from Murphy

            param_alpha = prior_alpha + param_N;        //Murphy eq. 21.139

            recompute_paramXBar(data);//eq. 21.146 from Murphy

            param_nu    = prior_nu + param_N + 1.0;  //Murphy eq. 21.145
            recompute_paramS(data);                  //equation 21.147 from Murphy (without the division by param_N at the end)
            param_beta  = prior_beta  + param_N;     //Murphy eq. 21.142

            recompute_paramM();//equation 21.143 from Murphy();
            recompute_paramL();

            resetPVarInGaussian(); // clean up some memory
        }

        private void recompute_paramM() {
            param_m = (prior_m.mapMultiply(prior_beta).add(param_xbar.mapMultiply(param_N))).mapDivide(param_beta);
        }

        private void recompute_paramL() {
            //eq 21.144 in Murphy
            final RealMatrix term1 = inverse(prior_L);
            final RealMatrix term2 = param_S.scalarMultiply(param_N);

            final RealVector muMinusPriorM = param_xbar.subtract(prior_m);
            final RealMatrix term3 = muMinusPriorM.outerProduct(muMinusPriorM).scalarMultiply((prior_beta * param_N) / (prior_beta + param_N));
            final RealMatrix sum = term1.add(term2).add(term3);                      //eq 21.144 in Murphy (third part)
            param_L = inverse(sum);
        }

        private void recompute_paramN(List<VariantDatum> data) {
            //this is equation 21.140 from Murphy
            param_N = 0.0;
            for (int i = 0; i < data.size(); i++) {
                final double prob = pVarInGaussian.get(i);
                param_N += prob;
            }
        }

        private void recompute_paramXBar(List<VariantDatum> data) {
            param_xbar.set(0.0);
            int datumIndex = 0;
            for (final VariantDatum datum : data) {
                final double prob = pVarInGaussian.get(datumIndex++);
                param_xbar = param_xbar.add(datum.annotations.mapMultiply(prob));
            }
            param_xbar.mapDivideToSelf(param_N);
        }

        private void recompute_paramS(List<VariantDatum> data) {
            RealMatrix pS = new Array2DRowRealMatrix(new double[dim][dim]);
            //equation 21.147 from Murphy
            int datumIndex = 0;
            for (final VariantDatum datum : data) {
                final double prob = pVarInGaussian.get(datumIndex++);
                RealVector rv = datum.annotations.subtract(param_xbar);
                final RealMatrix pVarSigma = rv.outerProduct(rv).scalarMultiply(prob);
                pS = pS.add(pVarSigma);
            }
            pS = pS.scalarMultiply(1.0 / param_N);
            param_S = pS;
        }

        double getpMixtureLog10() {
            return pMixtureLog10;
        }

        /**
         * Returns (a copy of) the mean vector.
         * A copy is made to avoid representation exposure.
         */
        RealVector getXBar() {
            return param_xbar.copy();
        }

        /**
         * Returns (a copy of) the covariance matrix.
         * A copy is made to avoid representation exposure.
         */
        RealMatrix getParam_S() {
            return param_S.copy();
        }

        void setpMixtureLog10(double pMixtureLog10) {
            this.pMixtureLog10 = pMixtureLog10;
        }

        @VisibleForTesting
        void setMu(RealVector mu) {
            this.param_xbar = mu.copy();
            this.param_m = mu.copy();
        }

        @VisibleForTesting
        void setParam_S(RealMatrix param_S) {
            this.param_S = param_S;
            this.param_L = inverse(param_S);
        }

        double getParam_N() {
            return param_N;
        }

        double getParam_alpha() {
            return param_alpha;
        }

        void setParam_N(double param_N) {
            this.param_N = param_N;
        }

        public void setParam_XBar(RealVector param_XBar) {
            this.param_xbar = param_XBar.copy();
        }
    }
}