package org.broadinstitute.hellbender.tools.spark.pipelines.metrics;


import org.broadinstitute.hellbender.cmdline.Argument;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;

import java.io.File;

public class SAMMetricsCollectorArgs {
    @Argument(fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME,
            shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME, doc = "File to write the output to.")
    public File output;
}
