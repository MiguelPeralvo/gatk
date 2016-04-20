package org.broadinstitute.hellbender.tools.spark.pipelines.metrics;

import htsjdk.samtools.SAMFileHeader;
import org.apache.spark.api.java.JavaRDD;
import org.broadinstitute.hellbender.cmdline.ArgumentCollection;
import org.broadinstitute.hellbender.cmdline.CommandLineProgramProperties;
import org.broadinstitute.hellbender.cmdline.programgroups.StructuralVariationSparkProgramGroup;
import org.broadinstitute.hellbender.engine.AuthHolder;
import org.broadinstitute.hellbender.engine.filters.ReadFilter;
import org.broadinstitute.hellbender.utils.read.GATKRead;

@CommandLineProgramProperties(
        summary        = "Program to collect insert size distribution information in SAM/BAM file(s)",
        oneLineSummary = "Collect Insert Size Distribution on Spark",
        programGroup   = StructuralVariationSparkProgramGroup.class)
public final class CollectInsertSizeMetricsCollectorSpark
        extends SamMetricsCollectorToolSpark<TInsertSizeMetricsCollectorSparkArgs> {

    private static final long serialVersionUID = 1L;

    @ArgumentCollection
    TInsertSizeMetricsCollectorSparkArgs inputArgs = new TInsertSizeMetricsCollectorSparkArgs();

    TInsertSizeMetricsCollectorSpark collector = new TInsertSizeMetricsCollectorSpark();

    @Override
    public TInsertSizeMetricsCollectorSparkArgs collectInputArguments() {
        return inputArgs;
    }

    public void initializeCollector(TInsertSizeMetricsCollectorSparkArgs inputArgs) {
        collector.initializeCollector(inputArgs);
    }

    /**
     * Expose the read filter required for this collector
     */
    @Override
    public ReadFilter getCollectorReadFilter(SAMFileHeader samHeader) {
        return collector.getCollectorReadFilter(samHeader);
    }

    @Override
    public void collectMetrics(
            SAMFileHeader samHeader,
            TInsertSizeMetricsCollectorSparkArgs args,
            AuthHolder authHolder,
            String inputName,
            JavaRDD<GATKRead> filteredReads) {

        collector.collectMetrics(
                samHeader,
                args,
                getAuthHolder(),
                getReadSourceName(),
                filteredReads
        );
    }
}
