package org.broadinstitute.hellbender.tools.spark.utils;

import com.google.common.annotations.VisibleForTesting;

import java.io.Serializable;
import java.util.*;

/**
 * Hash set that provides low memory overhead by using hopscotch hashing.
 * Also provides a special iterator type to traverse individual hash buckets.
 * (This lets you implement a multi-map on this collection by using a <K,V>-ish element type having a hashCode that
 * depends only on K.)
 */
public final class HopscotchHashSet<T> extends AbstractSet<T> implements Serializable {
    private static final long serialVersionUID = 1L;
    private int capacity;
    // unused buckets contain null.  (this data structure does not support null entries.)
    // if the bucket is unused, the corresponding status byte is irrelevant, but is always set to 0.
    private int size;
    private T[] buckets;
    // format of the status bytes:
    // high bit set indicates that the bucket contains "chain head" (i.e., an entry that naturally belongs in the
    // corresponding bucket).  high bit not set indicates a "squatter" (i.e., an entry that got placed here through the
    // collision resolution methodology).  we use Byte.MIN_VALUE (i.e., 0x80) to pick off this bit.
    // low 7 bits give the (unsigned) offset from the current entry to the next entry in the collision resolution chain.
    // if the low 7 bits are 0, then we'd be pointing at ourselves, which is nonsense, so that particular value marks
    // "end of chain" instead.  we use Byte.MAX_VALUE (i.e., 0x7f) to pick off these bits.
    private byte[] status;

    @VisibleForTesting static final double LOAD_FACTOR = .85;

    // largest prime numbers less than each half power of 2 from 2^8 to 2^31
    @VisibleForTesting static final int[] legalSizes = {
            251, 359, 509, 719, 1021, 1447, 2039, 2887, 4093, 5791, 8191, 11579, 16381, 23167, 32749, 46337, 65521,
            92681, 131071, 185363, 262139, 370723, 524287, 741431, 1048573, 1482907, 2097143, 2965819, 4194301, 5931641,
            8388593, 11863279, 16777213, 23726561, 33554393, 47453111, 67108859, 94906249, 134217689, 189812507,
            268435399, 379625047, 536870909, 759250111, 1073741789, 1518500213, 2147483647
    };

    @SuppressWarnings("unchecked")
    public HopscotchHashSet( final int capacity ) {
        this.capacity = computeCapacity(capacity);
        this.size = 0;
        // Not unsafe, because the remainder of the API allows only elements known to be T's to be assigned to buckets.
        this.buckets = (T[])new Object[this.capacity];
        this.status = new byte[this.capacity];
    }

    public HopscotchHashSet( final Collection<T> collection ) {
        this(collection.size());
        addAll(collection);
    }

    @Override
    public boolean contains( final Object value ) {
        if ( value == null ) return false;
        final Iterator<T> itr = bucketIterator(value.hashCode());
        while ( itr.hasNext() ) {
            if ( itr.next().equals(value) ) return true;
        }
        return false;
    }

    @Override
    public int size() { return size; }

    @Override
    public Iterator<T> iterator() { return new CompleteIterator(); }

    public Iterator<T> bucketIterator( final int hashVal ) { return new BucketIterator(hashVal); }

    @Override
    public boolean add( final T entry ) {
        if ( entry == null ) throw new UnsupportedOperationException("This collection cannot contain null.");
        if ( size == capacity ) resize();
        try {
            return insert(entry);
        } catch ( final IllegalStateException ise ) {
            resize();
            return insert(entry);
        }
    }

    @Override
    public boolean remove( final Object entry ) {
        if ( entry == null ) return false;
        int bucketIndex = hashToIndex(entry.hashCode());
        if ( buckets[bucketIndex] == null || !isChainHead(bucketIndex) ) return false;
        int predecessorIndex = -1;
        while ( true ) {
            final int offset = getOffset(bucketIndex);
            if ( buckets[bucketIndex].equals(entry) ) {
                if ( offset == 0 ) {
                    buckets[bucketIndex] = null;
                    status[bucketIndex] = 0;
                    if ( predecessorIndex != -1 ) status[predecessorIndex] -= getOffset(predecessorIndex);
                } else {
                    // move the item at the end of the chain into the hole we're creating by deleting this entry
                    int prevIndex = bucketIndex;
                    int nextIndex = getIndex(prevIndex, offset);
                    int offsetToNext;
                    while ( (offsetToNext = getOffset(nextIndex)) != 0 ) {
                        prevIndex = nextIndex;
                        nextIndex = getIndex(nextIndex, offsetToNext);
                    }
                    buckets[bucketIndex] = buckets[nextIndex];
                    buckets[nextIndex] = null;
                    status[prevIndex] -= getOffset(prevIndex);
                }
                size -= 1;
                return true;
            }
            if ( offset == 0 ) break;
            predecessorIndex = bucketIndex;
            bucketIndex = getIndex(bucketIndex, offset);
        }
        return false;
    }

    @Override
    public void clear() {
        for ( int idx = 0; idx != capacity; ++idx ) {
            buckets[idx] = null;
            status[idx] = 0;
        }
        size = 0;
    }

    private boolean insert( final T entry ) {
        final int bucketIndex = hashToIndex(entry.hashCode());

        // if there's a squatter where the new entry should go, move it elsewhere and put the entry there
        if ( buckets[bucketIndex] != null && !isChainHead(bucketIndex) ) evict(bucketIndex);

        // if the place where it should go is empty, just put the new entry there
        if ( buckets[bucketIndex] == null ) {
            buckets[bucketIndex] = entry;
            status[bucketIndex] = Byte.MIN_VALUE;
            size += 1;
            return true;
        }

        // make sure the entry isn't already present
        int endOfChainIndex = bucketIndex;
        while ( true ) {
            // if entry is already in the set
            if ( buckets[endOfChainIndex].equals(entry) ) return false;
            final int offset = getOffset(endOfChainIndex);
            if ( offset == 0 ) break;
            endOfChainIndex = getIndex(endOfChainIndex, offset);
        }

        // find a place for the new entry
        final int emptyBucketIndex = insertIntoChain(bucketIndex, endOfChainIndex);

        // put the new entry into the empty bucket
        buckets[emptyBucketIndex] = entry;
        size += 1;
        return true;
    }

    private int hashToIndex( final int hashVal ) {
        return Math.floorMod(hashVal, capacity);
    }

    private int insertIntoChain( final int bucketIndex, final int endOfChainIndex ) {
        final int offsetToEndOfChain = getIndexDiff(bucketIndex, endOfChainIndex);

        // find an empty bucket for the new entry
        int emptyBucketIndex = findEmptyBucket(bucketIndex);

        // if the distance to the empty bucket is larger than this, we'll have to hopscotch
        final int maxOffset = offsetToEndOfChain + Byte.MAX_VALUE;

        // hopscotch the empty bucket into range if it's too far away
        int offsetToEmpty;
        while ( (offsetToEmpty = getIndexDiff(bucketIndex, emptyBucketIndex)) > maxOffset ) {
            emptyBucketIndex = hopscotch(bucketIndex, emptyBucketIndex);
        }

        // if the new entry lies downstream of the current chain end, just link it in
        if ( offsetToEmpty > offsetToEndOfChain ) {
            status[endOfChainIndex] += offsetToEmpty - offsetToEndOfChain;
        } else {
            linkIntoChain(bucketIndex, emptyBucketIndex);
        }

        return emptyBucketIndex;
    }

    // walk the chain until we find where the new slot gets linked in
    private void linkIntoChain( final int bucketIndex, final int emptyBucketIndex ) {
        int offsetToEmpty = getIndexDiff(bucketIndex, emptyBucketIndex);
        int tmpIndex = bucketIndex;
        int offset;
        while ( (offset = getOffset(tmpIndex)) < offsetToEmpty ) {
            tmpIndex = getIndex(tmpIndex, offset);
            offsetToEmpty -= offset;
        }
        offset -= offsetToEmpty;
        status[tmpIndex] -= offset;
        status[emptyBucketIndex] = (byte) offset;
    }

    private void evict( final int bucketToEvictIndex ) {
        final int bucketIndex = hashToIndex(buckets[bucketToEvictIndex].hashCode());
        final int offsetToEvictee = getIndexDiff(bucketIndex, bucketToEvictIndex);
        int emptyBucketIndex = findEmptyBucket(bucketIndex);
        int fromIndex = bucketIndex;
        while ( true ) {
            while ( getIndexDiff(bucketIndex, emptyBucketIndex) > offsetToEvictee ) {
                emptyBucketIndex = hopscotch(fromIndex, emptyBucketIndex);
            }
            if ( emptyBucketIndex == bucketToEvictIndex ) return;
            fromIndex = emptyBucketIndex;
            linkIntoChain(bucketIndex, emptyBucketIndex);
            int prevIndex = bucketIndex;
            int offsetToNext = getOffset(prevIndex);
            int nextIndex = getIndex(prevIndex, offsetToNext);
            while ( (offsetToNext = getOffset(nextIndex)) != 0 ) {
                prevIndex = nextIndex;
                nextIndex = getIndex(nextIndex, offsetToNext);
            }
            buckets[emptyBucketIndex] = buckets[nextIndex];
            buckets[nextIndex] = null;
            status[nextIndex] = 0;
            status[prevIndex] -= getOffset(prevIndex);
            emptyBucketIndex = nextIndex;
        }
    }

    private int findEmptyBucket( int bucketIndex ) {
        do {
            bucketIndex = getIndex(bucketIndex, 1);
        }
        while ( buckets[bucketIndex] != null );
        return bucketIndex;
    }

    private boolean isChainHead( final int bucketIndex ) {
        return (status[bucketIndex] & Byte.MIN_VALUE) != 0;
    }

    private int getOffset( final int bucketIndex ) {
        return status[bucketIndex] & Byte.MAX_VALUE;
    }

    private int getIndex( final int bucketIndex, final int offset ) {
        int result = bucketIndex + offset;
        if ( result >= capacity ) result -= capacity;
        else if ( result < 0 ) result += capacity;
        return result;
    }

    // bucket1 is assumed to be upstream of bucket2 (even if bucket2's index has wrapped)
    // i.e., the result is always positive
    private int getIndexDiff( final int bucketIndex1, final int bucketIndex2 ) {
        int result = bucketIndex2 - bucketIndex1;
        if ( result < 0 ) result += capacity;
        return result;
    }

    private int hopscotch( final int fromIndex, final int emptyBucketIndex ) {
        final int fromToEmptyDistance = getIndexDiff(fromIndex, emptyBucketIndex);
        int offsetToEmpty = Byte.MAX_VALUE;
        while ( offsetToEmpty > 1 ) {
            final int bucketIndex = getIndex(emptyBucketIndex, -offsetToEmpty);
            final int offsetInBucket = getOffset(bucketIndex);
            if ( offsetInBucket != 0 &&
                    offsetInBucket < offsetToEmpty &&
                    offsetToEmpty-offsetInBucket < fromToEmptyDistance ) {
                final int bucketToMoveIndex = getIndex(bucketIndex, offsetInBucket);
                move(bucketIndex, bucketToMoveIndex, emptyBucketIndex);
                return bucketToMoveIndex;
            }
            offsetToEmpty -= 1;
        }
        throw new IllegalStateException("Hopscotching failed at load factor "+(1.*size/capacity));
    }

    private void move( int predecessorBucketIndex, final int bucketToMoveIndex, final int emptyBucketIndex ) {
        int toEmptyDistance = getIndexDiff(bucketToMoveIndex, emptyBucketIndex);
        int nextOffset = getOffset(bucketToMoveIndex);
        if ( nextOffset == 0 || nextOffset > toEmptyDistance ) {
            status[predecessorBucketIndex] += toEmptyDistance;
        } else {
            status[predecessorBucketIndex] += nextOffset;
            toEmptyDistance -= nextOffset;
            predecessorBucketIndex = getIndex(bucketToMoveIndex, nextOffset);
            while ( (nextOffset = getOffset(predecessorBucketIndex)) != 0 && nextOffset < toEmptyDistance ) {
                toEmptyDistance -= nextOffset;
                predecessorBucketIndex = getIndex(predecessorBucketIndex, nextOffset);
            }
            status[predecessorBucketIndex] = (byte) toEmptyDistance;
        }
        if ( nextOffset != 0 ) {
            status[emptyBucketIndex] = (byte) (nextOffset - toEmptyDistance);
        }
        buckets[emptyBucketIndex] = buckets[bucketToMoveIndex];
        buckets[bucketToMoveIndex] = null;
        status[bucketToMoveIndex] = 0;
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        final T[] oldBuckets = buckets;
        capacity = bumpCapacity(capacity);
        buckets = (T[])new Object[capacity];
        status = new byte[capacity];
        for ( final T entry : oldBuckets ) {
            if ( entry != null ) insert(entry);
        }
    }

    private static int computeCapacity( final int size ) {
        if ( size < LOAD_FACTOR*Integer.MAX_VALUE ) {
            final int augmentedSize = (int) (size / LOAD_FACTOR);
            for ( final int legalSize : legalSizes ) {
                if ( legalSize >= augmentedSize ) return legalSize;
            }
        }
        return legalSizes[legalSizes.length-1];
    }

    private static int bumpCapacity( final int capacity ) {
        if ( capacity <= legalSizes[legalSizes.length-2] ) {
            for ( int idx = 0; idx != legalSizes.length; ++idx ) {
                if ( legalSizes[idx] >= capacity ) return legalSizes[idx + 1];
            }
        }
        throw new IllegalStateException("Unable to increase capacity.");
    }

    private final class BucketIterator implements Iterator<T> {
        private int bucketIndex;

        BucketIterator( final int hashVal ) {
            final int bucketIndex = hashToIndex(hashVal);
            this.bucketIndex = isChainHead(bucketIndex) ? bucketIndex : buckets.length;
        }

        @Override public boolean hasNext() { return bucketIndex != buckets.length; }

        @Override public T next() {
            if ( bucketIndex == buckets.length ) throw new NoSuchElementException("HopscotchHashSet iterator is exhausted.");
            final T result = buckets[bucketIndex];
            if ( result == null ) throw new IllegalStateException("Unexpected null value during iteration.");
            final int offset = getOffset(bucketIndex);
            bucketIndex = offset != 0 ? getIndex(bucketIndex, offset) : buckets.length;
            return result;
        }
    }

    private final class CompleteIterator implements Iterator<T> {
        private int bucketIndex;
        private T lastEntry;

        CompleteIterator() { this.bucketIndex = findNext(capacity-1); }

        @Override public boolean hasNext() { return bucketIndex >= 0; }

        @Override public T next() {
            if ( bucketIndex < 0 ) throw new NoSuchElementException("HopscotchHashSet iterator is exhausted.");
            lastEntry = buckets[bucketIndex];
            bucketIndex = findNext(bucketIndex - 1);
            return lastEntry;
        }

        private int findNext( int index ) {
            while ( index >= 0 && buckets[index] == null ) {
                index -= 1;
            }
            return index;
        }
    }
}
