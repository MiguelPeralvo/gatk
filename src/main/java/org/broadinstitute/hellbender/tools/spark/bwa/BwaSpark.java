package org.broadinstitute.hellbender.tools.spark.bwa;

import com.github.lindenb.jbwa.jni.BwaIndex;
import com.github.lindenb.jbwa.jni.BwaMem;
import com.github.lindenb.jbwa.jni.ShortRead;
import com.google.common.base.Stopwatch;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterators;
import htsjdk.samtools.*;
import htsjdk.samtools.reference.ReferenceSequence;
import htsjdk.samtools.reference.ReferenceSequenceFile;
import htsjdk.samtools.reference.ReferenceSequenceFileFactory;
import htsjdk.samtools.util.StringUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.broadinstitute.hellbender.cmdline.Argument;
import org.broadinstitute.hellbender.cmdline.CommandLineProgramProperties;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.programgroups.SparkProgramGroup;
import org.broadinstitute.hellbender.engine.spark.GATKSparkTool;
import org.broadinstitute.hellbender.engine.spark.datasources.ReadsSparkSink;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.utils.gcs.BucketUtils;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.read.ReadsWriteFormat;
import org.broadinstitute.hellbender.utils.read.SAMRecordToGATKReadAdapter;
import org.seqdoop.hadoop_bam.BAMInputFormat;
import scala.Tuple2;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.broadinstitute.hellbender.utils.Utils.calcMD5;

@CommandLineProgramProperties(summary = "Runs BWA",
        oneLineSummary = "BWA on Spark",
        programGroup = SparkProgramGroup.class)
public final class BwaSpark extends GATKSparkTool {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LogManager.getLogger(BwaSpark.class);

    @Argument(doc = "the reference", shortName = "ref",
            fullName = "ref", optional = false)
    private String ref;

    @Argument(doc = "the output bam", shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME, optional = false)
    private String output;

    @Argument(doc = "the number of read pairs to send in a batch to BWA", shortName = "batchSize",
            fullName = "batchSize", optional = true)
    private int batchSize = 50;

    @Override
    public boolean requiresReads() {
        return true;
    }

    @Override
    protected void runTool(final JavaSparkContext ctx) {
        ctx.hadoopConfiguration().setBoolean(BAMInputFormat.KEEP_PAIRED_READS_TOGETHER_PROPERTY, true);
        JavaRDD<GATKRead> unalignedReads = getReads();
        JavaRDD<List<GATKRead>> unalignedPairs = unalignedReads.mapPartitions(iter -> () -> pairwise(iter));
        JavaRDD<Tuple2<ShortRead, ShortRead>> shortReadPairs = unalignedPairs.map(p -> {
            GATKRead read1 = p.get(0);
            GATKRead read2 = p.get(1);
            String name1 = read1.getName();
            String name2 = read2.getName();
            byte[] baseQualities1 = SAMUtils.phredToFastq(read1.getBaseQualities()).getBytes();
            byte[] baseQualities2 = SAMUtils.phredToFastq(read2.getBaseQualities()).getBytes();
            return new Tuple2<>(
                    new ShortRead(name1, read1.getBases(), baseQualities1),
                    new ShortRead(name2, read2.getBases(), baseQualities2));
        });

        JavaRDD<String> samLines = shortReadPairs.mapPartitions(iter -> () -> {
            System.loadLibrary("bwajni"); // Load native library in each task VM
            BwaIndex index = null;
            BwaMem mem = null;
            try {
                File localRef;
                if (BucketUtils.isHadoopUrl(ref)) {
                    Stopwatch stopwatch = Stopwatch.createStarted();
                    // copy to local file, since JNI currently requires it
                    localRef = File.createTempFile("ref", "fa");
                    Files.copy(Paths.get(URI.create(ref)), localRef.toPath());
                    stopwatch.stop();
                    log.info("Time to download reference: " + stopwatch.elapsed(TimeUnit.SECONDS) + "s");
                } else {
                    localRef = new File(ref);
                }
                index = new BwaIndex(localRef);
                mem = new BwaMem(index);
                return concat(batchIterator(mem, iter, batchSize));
            } catch (IOException e) {
                e.printStackTrace();
                throw new GATKException(e.toString());
            } finally {
                // TODO: closing index/mem causes problem when running tests in one VM
//                if (index != null) {
//                    index.close();
//                }
//                if (mem != null) {
//                    mem.dispose();
//                }
            }
        });

        // TODO: is there a better way to build a header? E.g. from the BAM

        final SAMSequenceDictionary sequences;
        try {
            sequences = BucketUtils.isHadoopUrl(ref) ?
                    makeSequenceDictionary(getPath(URI.create(ref))) : makeSequenceDictionary(new File(ref));
        } catch (IOException e) {
            e.printStackTrace();
            throw new GATKException(e.toString());
        }
        final SAMFileHeader readsHeader = new SAMFileHeader();
        readsHeader.setSortOrder(SAMFileHeader.SortOrder.unsorted);
        readsHeader.setSequenceDictionary(sequences);

        final SAMLineParser samLineParser = new SAMLineParser(new DefaultSAMRecordFactory(), ValidationStringency.SILENT, readsHeader, null, null);
        Broadcast<SAMLineParser> samLineParserBroadcast = ctx.broadcast(samLineParser);

        JavaRDD<GATKRead> reads = samLines.map(r -> new SAMRecordToGATKReadAdapter(samLineParserBroadcast.getValue().parseLine(r)));

        try {
            ReadsSparkSink.writeReads(ctx, output, null, reads, readsHeader, ReadsWriteFormat.SHARDED);
        } catch (IOException e) {
            throw new GATKException("unable to write bam: " + e);
        }

//        SAMFileWriterFactory samFileWriterFactory = new SAMFileWriterFactory();
//        try (SAMFileWriter samFileWriter = samFileWriterFactory.makeSAMWriter(readsHeader, true, new File(output))) {
//            for (GATKRead r : reads.collect()) {
//                samFileWriter.addAlignment(r.convertToSAMRecord(readsHeader));
//            }
//        }
    }

    private static Path getPath(URI uri) throws IOException {
        try {
            return Paths.get(uri);
        } catch (FileSystemNotFoundException e) {
            // Try to use thread context classloader to find the filesystem. This can happen when the filesystem
            // provider is loaded using a URL classloader (e.g. in spark-submit).
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                throw e;
            }
            return FileSystems.newFileSystem(uri, new HashMap<>(), cl).provider().getPath(uri);
        }
    }

    private <U> Iterator<List<U>> pairwise(Iterator<U> iter) {
        return Iterators.partition(iter, 2);
    }

    private Iterator<List<String>> batchIterator(final BwaMem bwaMem, Iterator<Tuple2<ShortRead, ShortRead>> iter, int batchSize) {
        return Iterators.transform(Iterators.partition(iter, batchSize), input -> {
            List<ShortRead> reads1 = new ArrayList<>();
            List<ShortRead> reads2 = new ArrayList<>();
            for (Tuple2<ShortRead, ShortRead> p : input) {
                reads1.add(p._1);
                reads2.add(p._2);
            }
            try {
                String[] alignments = bwaMem.align(reads1, reads2);
                return Arrays.asList(alignments);
            } catch (IOException e) {
                throw new GATKException(e.toString());
            }
        });
    }

    static <T> Iterator<T> concat(Iterator<? extends Iterable<T>> iterator) {
        return new AbstractIterator<T>() {
            Iterator<T> subIterator;
            @Override
            protected T computeNext() {
                if (subIterator != null && subIterator.hasNext()) {
                    return subIterator.next();
                }
                while (iterator.hasNext()) {
                    subIterator = iterator.next().iterator();
                    if (subIterator.hasNext()) {
                        return subIterator.next();
                    }
                }
                return endOfData();
            }
        };
    }

    // From CreateSequenceDictionary

    public int NUM_SEQUENCES = Integer.MAX_VALUE;

    SAMSequenceDictionary makeSequenceDictionary(final File referenceFile) {
        return makeSequenceDictionary(referenceFile.toPath());
    }

    SAMSequenceDictionary makeSequenceDictionary(final Path referenceFile) {
        final ReferenceSequenceFile refSeqFile =
                ReferenceSequenceFileFactory.getReferenceSequenceFile(referenceFile, true);
        ReferenceSequence refSeq;
        final List<SAMSequenceRecord> ret = new ArrayList<>();
        final Set<String> sequenceNames = new HashSet<>();
        for (int numSequences = 0; numSequences < NUM_SEQUENCES && (refSeq = refSeqFile.nextSequence()) != null; ++numSequences) {
            if (sequenceNames.contains(refSeq.getName())) {
                throw new UserException.MalformedFile("Sequence name appears more than once in reference: " + refSeq.getName());
            }
            sequenceNames.add(refSeq.getName());
            ret.add(makeSequenceRecord(refSeq));
        }
        return new SAMSequenceDictionary(ret);
    }

    /**
     * Create one SAMSequenceRecord from a single fasta sequence
     */
    private SAMSequenceRecord makeSequenceRecord(final ReferenceSequence refSeq) {
        final SAMSequenceRecord ret = new SAMSequenceRecord(refSeq.getName(), refSeq.length());

        // Compute MD5 of upcased bases
        final byte[] bases = refSeq.getBases();
        for (int i = 0; i < bases.length; ++i) {
            bases[i] = StringUtil.toUpperCase(bases[i]);
        }

        ret.setAttribute(SAMSequenceRecord.MD5_TAG, calcMD5(bases));
//        if (GENOME_ASSEMBLY != null) {
//            ret.setAttribute(SAMSequenceRecord.ASSEMBLY_TAG, GENOME_ASSEMBLY);
//        }
//        ret.setAttribute(SAMSequenceRecord.URI_TAG, URI);
//        if (SPECIES != null) {
//            ret.setAttribute(SAMSequenceRecord.SPECIES_TAG, SPECIES);
//        }
        return ret;
    }
}
