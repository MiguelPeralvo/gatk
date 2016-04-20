package org.broadinstitute.hellbender.tools.spark.pipelines.metrics;

import htsjdk.samtools.SAMFileHeader;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.broadinstitute.hellbender.cmdline.Argument;
import org.broadinstitute.hellbender.cmdline.CommandLineProgramProperties;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.programgroups.QCProgramGroup;
import org.broadinstitute.hellbender.engine.AuthHolder;
import org.broadinstitute.hellbender.engine.filters.ReadFilter;
import org.broadinstitute.hellbender.engine.spark.GATKSparkTool;
import org.broadinstitute.hellbender.utils.read.GATKRead;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Class that is designed to instantiate and execute multiple metrics programs that extend
 * SinglePassSamProgram while making only a single pass through the SAM file and supplying
 * each program with the records as it goes.
 *
 */
@CommandLineProgramProperties(
        summary = "Takes an input SAM/BAM file and reference sequence and runs one or more " +
                "metrics modules at the same time to cut down on I/O. Currently all programs are run with " +
                "default options and fixed output extesions, but this may become more flexible in future.",
        oneLineSummary = "A \"meta-metrics\" calculating program that produces multiple metrics for the provided SAM/BAM file",
        programGroup = QCProgramGroup.class
)
public final class CollectMultipleMetricsSpark extends GATKSparkTool {

    private static final long serialVersionUID = 1L;

    /**
     * This interface allows developers to create Programs to run in addition to the ones defined
     * in the Program enum.
     */
    private interface ProgramInterface {
        void doCollection(
                SAMFileHeader header,
                AuthHolder authHolder,
                String readSourceName,
                final String outbase,
                JavaRDD<GATKRead> reads);
    }

    //TODO: need a way to specify the collectors and collector-specific input arguments
    //TODO: we need to disambiguate the output names used by each collector

    public static enum Program implements ProgramInterface {
        CollectInsertSizeMetricsCollector1 {
            @Override
            public void doCollection(
                    SAMFileHeader header,
                    AuthHolder authHolder,
                    String readSourceName,
                    final String outbase,
                    JavaRDD<GATKRead> reads)
            {
                final TInsertSizeMetricsCollectorSpark collector = new TInsertSizeMetricsCollectorSpark();
                //program.OUTPUT = new File(outbase + ".alignment_summary_metrics");
                TInsertSizeMetricsCollectorSparkArgs isArgs = new TInsertSizeMetricsCollectorSparkArgs();
                isArgs.output = new File(outbase);
                isArgs.histogramPlotFile = outbase + ".pdf";
                isArgs.useEnd = TInsertSizeMetricsCollectorSparkArgs.EndToUse.SECOND;

                collector.initializeCollector(isArgs);
                // use the read filter provided by the collector instance
                ReadFilter readFilter = collector.getCollectorReadFilter(header);
                collector.collectMetrics(
                        header,
                        isArgs,
                        authHolder,
                        readSourceName,
                        reads.filter(r -> readFilter.test(r)));

                collector.finishCollection();
            }
        },
        CollectInsertSizeMetricsCollector2 {
            @Override
            public void doCollection(
                    SAMFileHeader header,
                    AuthHolder authHolder,
                    String readSourceName,
                    final String outbase,
                    JavaRDD<GATKRead> reads)
            {
                final TInsertSizeMetricsCollectorSpark collector = new TInsertSizeMetricsCollectorSpark();
                //program.OUTPUT = new File(outbase + ".alignment_summary_metrics");
                TInsertSizeMetricsCollectorSparkArgs isArgs = new TInsertSizeMetricsCollectorSparkArgs();
                isArgs.output = new File(outbase);
                isArgs.histogramPlotFile = outbase + ".pdf";
                isArgs.useEnd = TInsertSizeMetricsCollectorSparkArgs.EndToUse.SECOND;

                collector.initializeCollector(isArgs);
                // use the read filter provided by the collector instance
                ReadFilter readFilter = collector.getCollectorReadFilter(header);
                collector.collectMetrics(
                        header,
                        isArgs,
                        authHolder,
                        readSourceName,
                        reads.filter(r -> readFilter.test(r)));

                collector.finishCollection();
            }
        }
    }

    @Argument(doc = "If true (default), then the sort order in the header file will be ignored.",
            shortName = StandardArgumentDefinitions.ASSUME_SORTED_SHORT_NAME)
    public boolean ASSUME_SORTED = true;

    @Argument(doc = "Stop after processing N reads, mainly for debugging.")
    public int STOP_AFTER = 0;

    @Argument(fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME, shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            doc = "Base name of output files.")
    public String OUTPUT;

    //@Argument(doc = "List of metrics programs to apply during the pass through the SAM file.")
    //public List<Program> PROGRAM = CollectionUtil.makeList(Program.values());

    /**
     * Contents of PROGRAM list is transferred to this list during command-line validation, so that an outside
     * developer can invoke this class programmatically and provide alternative Programs to run by calling
     * setProgramsToRun().
     */
    private List<ProgramInterface> programsToRun = Arrays.asList(
            Program.CollectInsertSizeMetricsCollector1,
            Program.CollectInsertSizeMetricsCollector2);

    @Override
    protected String[] customCommandLineValidation() {
    //        programsToRun = new ArrayList<>(PROGRAM);
        return super.customCommandLineValidation();
    }

    /**
     * Use this method when invoking CollectMultipleMetrics programmatically to run programs other than the ones
     * available via enum.  This must be called before doWork().
     */
    public void setProgramsToRun(List<ProgramInterface> programsToRun) {
        this.programsToRun = programsToRun;
    }

    @Override
    protected void runTool( JavaSparkContext ctx ) {
        final JavaRDD<GATKRead> unFilteredReads = getUnfilteredReads();
        if (programsToRun.size() > 0) {
            // if there is more than one program to run, cache the unfiltered RDD
            // so we don't recompute it
            unFilteredReads.cache();
        }

        for (ProgramInterface program : programsToRun) {
            program.doCollection(
                    getHeaderForReads(),
                    getAuthHolder(),
                    getReadSourceName(),
                    OUTPUT,
                    unFilteredReads
            );
        }
    }

}
