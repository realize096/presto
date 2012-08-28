package com.facebook.presto.operators;

import com.facebook.presto.BlockBuilder;
import com.facebook.presto.BlockStream;
import com.facebook.presto.Cursor;
import com.facebook.presto.RunLengthEncodedBlock;
import com.facebook.presto.Tuple;
import com.facebook.presto.TupleInfo;
import com.facebook.presto.TupleInfo.Type;
import com.facebook.presto.UncompressedCursor;
import com.facebook.presto.UncompressedValueBlock;
import com.facebook.presto.ValueBlock;
import com.facebook.presto.aggregations.AggregationFunction;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;

import javax.inject.Provider;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Group input data and produce a single block for each sequence of identical values.
 */
public class HashAggregationBlockStream
    implements BlockStream<UncompressedValueBlock>
{
    private final BlockStream<RunLengthEncodedBlock> groupBySource;
    private final BlockStream<? extends ValueBlock> aggregationSource;
    private final Provider<AggregationFunction> functionProvider;
    private final TupleInfo info;

    public HashAggregationBlockStream(BlockStream<RunLengthEncodedBlock> groupBySource,
            BlockStream<? extends ValueBlock> aggregationSource,
            Provider<AggregationFunction> functionProvider)
    {
        this.groupBySource = groupBySource;
        this.aggregationSource = aggregationSource;
        this.functionProvider = functionProvider;

        info = new TupleInfo(ImmutableList.<Type>builder()
                .addAll(groupBySource.getTupleInfo().getTypes())
                .addAll(functionProvider.get().getTupleInfo().getTypes())
                .build());

    }

    @Override
    public TupleInfo getTupleInfo()
    {
        return info;
    }

    @Override
    public Cursor cursor()
    {
        return new UncompressedCursor(getTupleInfo(), iterator());
    }

    @Override
    public Iterator<UncompressedValueBlock> iterator()
    {
        final Iterator<RunLengthEncodedBlock> groupByIterator = groupBySource.iterator();
//        final SeekableIterator<? extends ValueBlock> aggregationIterator = new ForwardingSeekableIterator<>(aggregationSource.iterator());
        final Cursor aggregationCursor = aggregationSource.cursor();
        aggregationCursor.advanceNextPosition();

        return new AbstractIterator<UncompressedValueBlock>()
        {
            private Iterator<Entry<Tuple, AggregationFunction>> aggregations;
            private long position;

            @Override
            protected UncompressedValueBlock computeNext()
            {
                // process all data ahead of time
                if (aggregations == null) {
                    Map<Tuple, AggregationFunction> aggregationMap = new HashMap<>();
                    while (groupByIterator.hasNext()) {
                        RunLengthEncodedBlock group = groupByIterator.next();

                        AggregationFunction aggregation = aggregationMap.get(group.getValue());
                        if (aggregation == null) {
                            aggregation = functionProvider.get();
                            aggregationMap.put(group.getValue(), aggregation);
                        }
                        aggregation.add(aggregationCursor, group.getRange());
                    }

                    this.aggregations = aggregationMap.entrySet().iterator();
                }

                // if no more data, return null
                if (!aggregations.hasNext()) {
                    endOfData();
                    return null;
                }

                BlockBuilder blockBuilder = new BlockBuilder(position, info);
                while (!blockBuilder.isFull() && aggregations.hasNext()) {
                    // get next aggregation
                    Entry<Tuple, AggregationFunction> aggregation = aggregations.next();

                    // calculate final value for this group
                    Tuple key = aggregation.getKey();
                    Tuple value = aggregation.getValue().evaluate();
                    blockBuilder.append(key);
                    blockBuilder.append(value);
                }

                // build output block
                UncompressedValueBlock block = blockBuilder.build();

                // update position
                position += block.getCount();

                return block;
            }
        };
    }
}
