package com.doc.docquery.retrieval;

import com.doc.docquery.config.DocumentRetrievalProperties;
import com.doc.docquery.parser.EvidenceBlock;
import com.doc.docquery.service.RetrievalCardChatGateway;
import com.doc.docquery.service.RetrievalGenerationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从模型语义候选中选择少量连续范围；不会按固定窗口发明边界。 */
@Component
public class NavigationPartitionPlanner {

    private final DocumentRetrievalProperties properties;

    public NavigationPartitionPlanner(DocumentRetrievalProperties properties) {
        this.properties = properties;
    }

    public int estimateTokens(List<EvidenceBlock> blocks) {
        long estimate = 0;
        for (EvidenceBlock block : blocks) {
            estimate += estimateTokens(block.text());
        }
        return Math.toIntExact(Math.max(1L, estimate));
    }

    public int estimateTokens(String text) {
        String value = text == null ? "" : text;
        long ascii = 0;
        long nonAscii = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (codePoint <= 0x7f) {
                ascii++;
            } else {
                nonAscii++;
            }
            offset += Character.charCount(codePoint);
        }
        return Math.toIntExact(Math.max(1L, (ascii + 3L) / 4L + nonAscii));
    }

    public PartitionBounds bounds(int estimatedTokens) {
        int minimum = Math.max(2, divideCeil(
                estimatedTokens,
                properties.getNavigationPartitionThresholdTokens()
        ));
        if (minimum > properties.getNavigationPartitionMaxCount()) {
            throw limit("Oversized section needs too many navigation partitions");
        }
        return new PartitionBounds(
                minimum,
                Math.min(properties.getNavigationPartitionMaxCount(), minimum + 1)
        );
    }

    public List<NavigationPartition> plan(
            List<EvidenceBlock> blocks,
            List<RetrievalCardChatGateway.BoundaryCandidate> rawCandidates
    ) {
        if (blocks == null || blocks.isEmpty() || rawCandidates == null) {
            throw invalid("Boundary candidates are missing");
        }
        int totalTokens = estimateTokens(blocks);
        PartitionBounds bounds = bounds(totalTokens);
        if (rawCandidates.size() < bounds.minimum()
                || rawCandidates.size() > properties.getNavigationPartitionMaxCandidates()) {
            throw invalid("Boundary candidate count is invalid");
        }

        Map<Integer, EvidenceBlock> blockByOrdinal = new LinkedHashMap<>();
        for (EvidenceBlock block : blocks) {
            blockByOrdinal.put(block.ordinal(), block);
        }
        int sectionStart = blocks.get(0).ordinal();
        int sectionEnd = blocks.get(blocks.size() - 1).ordinal() + 1;
        List<ValidatedCandidate> candidates = new ArrayList<>();
        int previous = -1;
        for (RetrievalCardChatGateway.BoundaryCandidate candidate : rawCandidates) {
            if (candidate == null || !blockByOrdinal.containsKey(candidate.startBlockOrdinal())
                    || candidate.startBlockOrdinal() <= previous
                    || candidate.title() == null || candidate.title().isBlank()
                    || candidate.title().strip().length() > 120
                    || candidate.boundaryStrength() < 1 || candidate.boundaryStrength() > 5
                    || candidate.topics() == null || candidate.topics().isEmpty()
                    || candidate.topics().size() > 8
                    || candidate.topics().stream().anyMatch(topic -> topic == null
                    || topic.isBlank() || topic.strip().length() > 80)) {
                throw invalid("Boundary candidate structure is invalid");
            }
            previous = candidate.startBlockOrdinal();
            candidates.add(new ValidatedCandidate(
                    candidate.title().strip(),
                    candidate.startBlockOrdinal(),
                    candidate.boundaryStrength(),
                    candidate.topics().stream().map(String::strip).toList()
            ));
        }
        if (candidates.get(0).startBlockOrdinal() != sectionStart) {
            throw invalid("Boundary candidates do not start at the section boundary");
        }

        Map<Integer, Integer> blockIndex = new HashMap<>();
        int[] prefix = new int[blocks.size() + 1];
        for (int index = 0; index < blocks.size(); index++) {
            blockIndex.put(blocks.get(index).ordinal(), index);
            prefix[index + 1] = prefix[index] + estimateTokens(List.of(blocks.get(index)));
        }
        List<Integer> starts = candidates.stream()
                .map(ValidatedCandidate::startBlockOrdinal).toList();
        Map<Integer, ValidatedCandidate> byStart = candidates.stream().collect(
                java.util.stream.Collectors.toMap(
                        ValidatedCandidate::startBlockOrdinal,
                        candidate -> candidate,
                        (left, right) -> left,
                        LinkedHashMap::new
                )
        );

        for (int count = bounds.minimum(); count <= bounds.maximum(); count++) {
            List<Integer> selected = select(
                    starts,
                    byStart,
                    blockIndex,
                    prefix,
                    sectionEnd,
                    totalTokens,
                    count
            );
            if (selected != null) {
                List<NavigationPartition> result = new ArrayList<>();
                for (int index = 0; index < selected.size(); index++) {
                    int start = selected.get(index);
                    int end = index + 1 < selected.size()
                            ? selected.get(index + 1) : sectionEnd;
                    ValidatedCandidate candidate = byStart.get(start);
                    result.add(new NavigationPartition(
                            index,
                            candidate.title(),
                            start,
                            end,
                            tokensBetween(start, end, blockIndex, prefix, sectionEnd),
                            candidate.boundaryStrength(),
                            candidate.topics()
                    ));
                }
                return List.copyOf(result);
            }
        }
        throw invalid("Boundary candidates cannot satisfy partition constraints");
    }

    private List<Integer> select(
            List<Integer> starts,
            Map<Integer, ValidatedCandidate> byStart,
            Map<Integer, Integer> blockIndex,
            int[] prefix,
            int sectionEnd,
            int totalTokens,
            int desiredCount
    ) {
        double target = (double) totalTokens / desiredCount;
        Map<Integer, State> states = Map.of(0, new State(0, 0.0, List.of(starts.get(0))));
        for (int selected = 1; selected < desiredCount; selected++) {
            Map<Integer, State> next = new HashMap<>();
            for (Map.Entry<Integer, State> entry : states.entrySet()) {
                int previousIndex = entry.getKey();
                State state = entry.getValue();
                for (int candidateIndex = previousIndex + 1;
                     candidateIndex < starts.size(); candidateIndex++) {
                    int tokens = tokensBetween(
                            starts.get(previousIndex), starts.get(candidateIndex),
                            blockIndex, prefix, sectionEnd
                    );
                    if (tokens < properties.getNavigationPartitionMinimumTokens()) {
                        continue;
                    }
                    if (tokens > properties.getNavigationPartitionThresholdTokens()) {
                        break;
                    }
                    List<Integer> path = new ArrayList<>(state.path());
                    path.add(starts.get(candidateIndex));
                    State candidate = new State(
                            state.strength() + byStart.get(starts.get(candidateIndex))
                                    .boundaryStrength(),
                            state.negativeImbalance() - Math.abs(tokens - target),
                            List.copyOf(path)
                    );
                    State current = next.get(candidateIndex);
                    if (current == null || candidate.betterThan(current)) {
                        next.put(candidateIndex, candidate);
                    }
                }
            }
            states = next;
            if (states.isEmpty()) {
                return null;
            }
        }
        State best = null;
        for (Map.Entry<Integer, State> entry : states.entrySet()) {
            int finalTokens = tokensBetween(
                    starts.get(entry.getKey()), sectionEnd,
                    blockIndex, prefix, sectionEnd
            );
            if (finalTokens < properties.getNavigationPartitionMinimumTokens()
                    || finalTokens > properties.getNavigationPartitionThresholdTokens()) {
                continue;
            }
            State candidate = new State(
                    entry.getValue().strength(),
                    entry.getValue().negativeImbalance() - Math.abs(finalTokens - target),
                    entry.getValue().path()
            );
            if (best == null || candidate.betterThan(best)) {
                best = candidate;
            }
        }
        return best == null ? null : best.path();
    }

    private int tokensBetween(
            int start,
            int end,
            Map<Integer, Integer> blockIndex,
            int[] prefix,
            int sectionEnd
    ) {
        Integer left = blockIndex.get(start);
        Integer right = end == sectionEnd ? prefix.length - 1 : blockIndex.get(end);
        if (left == null || right == null || left >= right) {
            throw invalid("Navigation partition range is invalid");
        }
        return prefix[right] - prefix[left];
    }

    private int divideCeil(int value, int divisor) {
        if (divisor < 1) {
            throw new IllegalStateException("Navigation partition threshold is invalid");
        }
        return (value + divisor - 1) / divisor;
    }

    private RetrievalGenerationException invalid(String message) {
        return new RetrievalGenerationException(
                "RETRIEVAL_MODEL_OUTPUT_INVALID", message, false
        );
    }

    private RetrievalGenerationException limit(String message) {
        return new RetrievalGenerationException(
                "RETRIEVAL_GENERATION_LIMIT_EXCEEDED", message, false
        );
    }

    public record PartitionBounds(int minimum, int maximum) {
    }

    public record NavigationPartition(
            int partitionOrdinal,
            String title,
            int startBlockOrdinal,
            int endBlockOrdinalExclusive,
            int estimatedTokens,
            int boundaryStrength,
            List<String> topics
    ) {
        public NavigationPartition {
            topics = List.copyOf(topics);
        }
    }

    private record ValidatedCandidate(
            String title,
            int startBlockOrdinal,
            int boundaryStrength,
            List<String> topics
    ) {
    }

    private record State(int strength, double negativeImbalance, List<Integer> path) {
        private boolean betterThan(State other) {
            return strength > other.strength
                    || strength == other.strength
                    && negativeImbalance > other.negativeImbalance;
        }
    }
}
