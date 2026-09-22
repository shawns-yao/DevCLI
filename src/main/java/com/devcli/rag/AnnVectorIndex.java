package com.devcli.rag;

import com.github.jelmerk.hnswlib.core.DistanceFunctions;
import com.github.jelmerk.hnswlib.core.Item;
import com.github.jelmerk.hnswlib.core.hnsw.HnswIndex;

import java.util.ArrayList;
import java.util.List;

/** 进程内 HNSW 索引；SQLite 仍是向量和代码块元数据的持久化真相源。 */
final class AnnVectorIndex {
    private static final int M = 16;
    private static final int EF_CONSTRUCTION = 200;
    private static final int EF_SEARCH = 100;

    private final HnswIndex<Long, float[], VectorItem, Float> index;

    private AnnVectorIndex(int dimensions, int capacity) {
        this.index = HnswIndex
                .<float[], Float>newBuilder(dimensions, DistanceFunctions.FLOAT_COSINE_DISTANCE,
                        Math.max(1, capacity))
                .withM(M)
                .withEfConstruction(EF_CONSTRUCTION)
                .withEf(EF_SEARCH)
                .build();
    }

    static AnnVectorIndex build(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        int dimensions = entries.getFirst().vector().length;
        if (dimensions == 0) {
            throw new IllegalArgumentException("向量维度不能为 0");
        }
        AnnVectorIndex result = new AnnVectorIndex(dimensions, entries.size());
        for (Entry entry : entries) {
            if (entry.vector() == null || entry.vector().length != dimensions) {
                throw new IllegalArgumentException("索引中的向量维度不一致");
            }
            if (!result.index.add(new VectorItem(entry.id(), entry.vector()))) {
                throw new IllegalArgumentException("向量主键重复: " + entry.id());
            }
        }
        return result;
    }

    List<Neighbor> search(float[] query, int topK) {
        if (query == null || query.length != index.getDimensions()) {
            throw new IllegalArgumentException("查询向量维度与索引不一致");
        }
        int limit = Math.min(Math.max(0, topK), index.size());
        if (limit == 0) {
            return List.of();
        }
        List<Neighbor> results = new ArrayList<>(limit);
        for (com.github.jelmerk.hnswlib.core.SearchResult<VectorItem, Float> match
                : index.findNearest(query, limit)) {
            results.add(new Neighbor(match.item().id(), 1.0d - match.distance()));
        }
        return List.copyOf(results);
    }

    int size() {
        return index.size();
    }

    record Entry(long id, float[] vector) {
    }

    record Neighbor(long id, double similarity) {
    }

    private record VectorItem(Long id, float[] vector) implements Item<Long, float[]> {
        @Override
        public int dimensions() {
            return vector.length;
        }
    }
}
