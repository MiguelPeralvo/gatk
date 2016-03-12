package org.broadinstitute.hellbender.utils;

import htsjdk.tribble.Tribble;
import htsjdk.tribble.index.Index;
import htsjdk.tribble.index.IndexFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.broadinstitute.hellbender.exceptions.UserException;

import java.io.File;

public final class IndexUtils {
    private IndexUtils(){}

    private static final Logger logger = LogManager.getLogger(IndexUtils.class);

    /**
     * Load the index from disk, checking for out of date indexes and old versions
     * @return an Index, or null if we're unable to load
     */
    public static Index loadTribbleIndex(final File featureFile) {
        Utils.nonNull(featureFile);
        final File indexFile = Tribble.indexFile(featureFile);
        if (! indexFile.canRead()) {
            return null;
        }
        logger.debug("Loading Tribble index from disk for file " + featureFile);
        final Index index = IndexFactory.loadIndex(indexFile.getAbsolutePath());
        checkIndexModificationTime(featureFile, indexFile, index);
        return index;
    }

    /**
     * @throws UserException the index not up-to date (ie older than the feature file) and is not the current version.
     */
    public static void checkIndexModificationTime(final File featureFile, final File indexFile, final Index index) {
        if (! index.isCurrentVersion()) {
            // we've loaded an old version of the index, we want to remove it <-- currently not used, but may re-enable
            throw new UserException("Index file " + indexFile + " is out of date (old version). Use IndexFeatureFile to make an index.");
        } else if (indexFile.lastModified() < featureFile.lastModified()) {
            throw new UserException("Index file " + indexFile + " is out of date (index older than input file). Use IndexFeatureFile to make an index.");
        }
    }
}
