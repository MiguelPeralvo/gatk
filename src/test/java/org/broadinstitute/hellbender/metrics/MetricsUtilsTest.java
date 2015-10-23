package org.broadinstitute.hellbender.metrics;

import htsjdk.samtools.metrics.MetricBase;
import htsjdk.samtools.metrics.MetricsFile;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.broadinstitute.hellbender.utils.gcs.BucketUtils;
import org.broadinstitute.hellbender.utils.test.BaseTest;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;

public class MetricsUtilsTest extends BaseTest {
    private MiniDFSCluster cluster;
    private String hdfsWorkingDir;

    @BeforeClass
    private void setupMiniCluster() throws IOException {
        cluster = new MiniDFSCluster.Builder(new Configuration()).build();
        hdfsWorkingDir = cluster.getFileSystem().getWorkingDirectory().toString();
    }

    @AfterClass
    public void shutdownMiniCluster() {
        if (cluster != null) {
            cluster.shutdown();
        }
    }

    @DataProvider(name = "metricsPaths")
    public Object[][] getMetricsPaths(){
        return new Object[][]{
                {"metrics"},
                {getGCPTestStaging()},
                {hdfsWorkingDir}
        };
    }

    public static class TestMetric extends MetricBase {
        public Integer value1 = 0;
        public Integer value2 = 0;
    }

    @Test(dataProvider = "metricsPaths", groups = "cloud")
    public void testSaveMetrics(String destinationPrefix) throws IOException {
        final String outputPath = BucketUtils.getTempFilePath(destinationPrefix, ".txt", getAuthentication());
        TestMetric testMetric = new TestMetric();
        testMetric.value1 = 10;
        testMetric.value2 = 5;

        final MetricsFile<TestMetric, ?> metrics = new MetricsFile<>();
        metrics.addMetric(testMetric);
        MetricsUtils.saveMetrics(metrics, outputPath,getAuthentication());
        Assert.assertTrue(BucketUtils.fileExists(outputPath, getAuthentication()));
        File localCopy = copyFileToLocalTmpFile(outputPath);

        final File expectedMetrics = createTempFile("expectedMetrics", ".txt");
        metrics.write(expectedMetrics);

        Assert.assertTrue(MetricsFile.areMetricsEqual(localCopy, expectedMetrics));
    }

    private File copyFileToLocalTmpFile(String outputPath) throws IOException {
        File localCopy = createTempFile("local_metrics_copy",".txt");
        BucketUtils.copyFile(outputPath, getAuthentication(), localCopy.getAbsolutePath());
        return localCopy;
    }
}