package org.broadinstitute.hellbender.tools.spark.pipelines.metrics;


import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.broadinstitute.hellbender.engine.filters.ReadFilter;
import org.broadinstitute.hellbender.engine.spark.GATKSparkTool;
import org.broadinstitute.hellbender.utils.read.GATKRead;

/**
 *
 * Base class for individual Spark metrics collector tools, which are specializations
 * of GATKSparkTool that implement the SamMetricsCollectorSpark interface.
 */
public abstract class SamMetricsCollectorToolSpark<ARGS extends SAMMetricsCollectorArgs>
        extends GATKSparkTool
        implements SamMetricsCollectorSpark<ARGS> {

    private static final long serialVersionUID = 1l;

    @Override
    public final boolean requiresReads(){ return true; }

    @Override
    public final ReadFilter makeReadFilter() {
        return getCollectorReadFilter(getHeaderForReads());
    }

    /**
     * The runTool method used when the derived metrics collector tool is run
     * "standalone". The filtered RDD is passed the doMetricsCollection
     * method which does the bulk of the analysis. When a metrics collector is
     * run on behalf of CollectMultipleMetricsSpark, this will bypassed  and
     * doMetricsCollection will be passed the input RDD obtained by
     * CollectMultipleMetricsSpark.
     *
     * @param ctx our Spark context
     */

    @Override
    protected void runTool( JavaSparkContext ctx ) {
        ARGS args = collectInputArguments();
        initializeCollector(args);
        final JavaRDD<GATKRead> filteredReads = getReads();
        collectMetrics(
                getHeaderForReads(),
                args,
                getAuthHolder(),
                getReadSourceName(),
                filteredReads);
        finishCollection();
    }

}
