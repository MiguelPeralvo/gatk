package org.broadinstitute.hellbender.tools.spark.bwa;

import com.google.cloud.dataflow.sdk.repackaged.com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.broadinstitute.hellbender.utils.test.BaseTest;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

public final class BwaSparkUnitTest  extends BaseTest {

    @Test
    public void test() {
        check(ImmutableList.of(ImmutableList.of()), ImmutableList.of());
        check(ImmutableList.of(ImmutableList.of("a")), ImmutableList.of("a"));
        check(ImmutableList.of(ImmutableList.of("a", "b")), ImmutableList.of("a", "b"));
        check(ImmutableList.of(ImmutableList.of("a"), ImmutableList.of("b")), ImmutableList.of("a", "b"));
        check(ImmutableList.of(ImmutableList.of(), ImmutableList.of("a"), ImmutableList.of("b")), ImmutableList.of("a", "b"));
        check(ImmutableList.of(ImmutableList.of("a"), ImmutableList.of(), ImmutableList.of("b")), ImmutableList.of("a", "b"));
        check(ImmutableList.of(ImmutableList.of("a"), ImmutableList.of("b"), ImmutableList.of()), ImmutableList.of("a", "b"));
        check(ImmutableList.of(ImmutableList.of("a", "b"), ImmutableList.of("c", "d")),
                ImmutableList.of("a", "b", "c", "d"));
    }

    private <T> void check(List<? extends Iterable<T>> input, List<T> expected) {
        Assert.assertEquals(Lists.newArrayList(BwaSpark.concat(input.iterator())), expected);
    }

}
