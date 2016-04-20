package org.broadinstitute.hellbender.tools.spark.pipelines.metrics;

import htsjdk.samtools.SAMFileHeader;

import org.apache.spark.api.java.JavaRDD;
import org.broadinstitute.hellbender.engine.AuthHolder;
import org.broadinstitute.hellbender.engine.filters.ReadFilter;
import org.broadinstitute.hellbender.utils.read.GATKRead;

/**
 * Interface implemented by Spark SAM metrics collectors. Collectors should
 * be written to this interface in order to allow them to run either as a
 * standalone tool that is a subclass of SamMetricsCollectorSpark (i.e
 * CollectInsertSizeMetrics), or under the control of CollectMultipleMetrics,
 * which reuses the same input RDD to run multiple collectors. This imposes
 * a specialized structure on  metrics collector tools that separates the
 * analysis of the RDD ahost tool obtaining the RDD.cquisition of the from
 */
public interface SamMetricsCollectorSpark<ARGS extends SAMMetricsCollectorArgs> {

    /**
     * Collect and return any input argument values specific to this collector
     * type.
     * @return object of type ARGS
     */
    default ARGS collectInputArguments() { return null; };

    /**
     * Initialize the collector with input arguments;
     */
    void initializeCollector(ARGS inputArgs);

    /**
     * Expose the read filter required for this collector
     */
    ReadFilter getCollectorReadFilter(SAMFileHeader samHeader);

    /**
     * Do the actual metrics collection on the provided RDD.
     * @param samHeader The SAMFileHeader associated with the reads in the input RDD.
     * @param filteredReads The reads to be analyzed for this collector.
     */
    void collectMetrics(
            SAMFileHeader samHeader,
            ARGS collectorArgs,
            AuthHolder authHolder,
            String inputName,
            JavaRDD<GATKRead> filteredReads
    );

    default void finishCollection() { }
}
