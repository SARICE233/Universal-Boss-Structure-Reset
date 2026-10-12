package com.structurereset.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Picks the single reset definition that applies to one structure/anchor event.
 *
 * <p>The trigger decides which definitions are candidates at all. Every surviving
 * candidate is then ranked, best (lowest) rank first:</p>
 *
 * <ol>
 *   <li>rank {@code 0} - the definition lists the exact structure id that was
 *       entered;</li>
 *   <li>rank {@code 1} - the definition's
 *       {@link ResetDefinition#respawnBossEntity()} strictly equals the anchor
 *       block's stored entity type ({@link String#equals(Object)}, case
 *       sensitive, namespace included);</li>
 *   <li>rank {@code 2} - the definition matches one of the structure tags;</li>
 *   <li>rank {@code 3} - the definition matched the anchor block only.</li>
 * </ol>
 *
 * <p>Candidates with the same rank are ordered by descending priority and then
 * by ascending definition id.</p>
 *
 * <p><strong>Anchor entity veto.</strong> When the anchor entity type is
 * non-null and non-blank a definition is ineligible (rank {@code -1}) only when
 * its {@link ResetDefinition#respawnBossEntity()} differs from that entity type
 * <em>and</em> it does not list the entered structure id exactly. An exact
 * structure id match therefore stays eligible and keeps rank {@code 0} even
 * when the definition respawns another boss, while a definition that only
 * matched the anchor block or a structure tag is refused as soon as it respawns
 * a different boss. A null or blank anchor entity type disables both the veto
 * and the entity tier, which is the historical behaviour: the exact structure id
 * tier (rank {@code 0}) and the pure anchor tier (rank {@code 3}) both stay
 * reachable.</p>
 *
 * <p><strong>Ambiguity.</strong> When more than one candidate shares the best
 * rank the selection is refused with {@link Optional#empty()} instead of
 * guessing, unless that best rank is {@code 0} - the exact structure id match -
 * in which case the tie is broken deterministically by descending priority and
 * then ascending definition id. That exception preserves the historical
 * exact-id tie-break for legacy callers and for two definitions that claim the
 * same structure id.</p>
 */
public final class DefinitionSelector {

    private DefinitionSelector() {
    }

    /**
     * Selects the definition to use, or {@link Optional#empty()} when none
     * applies. Null collections are treated as empty.
     *
     * <p>This overload keeps the historical behaviour: it performs no anchor
     * entity filtering, so the exact structure id tier and the pure anchor tier
     * both stay reachable.</p>
     *
     * @param definitions every known definition
     * @param structureId the structure the player entered, or null
     * @param structureTags the tags the structure carries, with or without '#'
     * @param anchorMatched true when the anchor block was triggered
     */
    public static Optional<ResetDefinition> select(Collection<ResetDefinition> definitions,
                                                   String structureId,
                                                   Collection<String> structureTags,
                                                   boolean anchorMatched) {
        return select(definitions, structureId, structureTags, anchorMatched, null);
    }

    /**
     * Selects the definition to use, or {@link Optional#empty()} when none
     * applies or when the best rank is ambiguous. Null collections are treated
     * as empty.
     *
     * <p>An exact structure id match wins (rank {@code 0}) over a definition that
     * only respawns the anchor entity (rank {@code 1}). A non-blank anchor entity
     * type vetoes every definition whose respawned boss differs from it unless
     * that definition lists the entered structure id exactly. A null or blank
     * anchor entity type disables the veto and the entity tier entirely.</p>
     *
     * @param definitions every known definition
     * @param structureId the structure the player entered, or null
     * @param structureTags the tags the structure carries, with or without '#'
     * @param anchorMatched true when the anchor block was triggered
     * @param anchorEntityType the entity type stored by the triggered anchor
     *        block, or null/blank when it has none; when set, a definition whose
     *        respawned boss differs is only eligible when it lists the entered
     *        structure id exactly
     */
    public static Optional<ResetDefinition> select(Collection<ResetDefinition> definitions,
                                                   String structureId,
                                                   Collection<String> structureTags,
                                                   boolean anchorMatched,
                                                   String anchorEntityType) {
        if (definitions == null || definitions.isEmpty()) {
            return Optional.empty();
        }
        Set<String> tags = normalizeTags(structureTags);
        List<Candidate> candidates = new ArrayList<>();
        for (ResetDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            int rank = rank(definition, structureId, tags, anchorMatched, anchorEntityType);
            if (rank >= 0) {
                candidates.add(new Candidate(definition, rank,
                        matchedExactStructureId(definition, structureId)));
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        candidates.sort((left, right) -> {
            int byRank = Integer.compare(left.rank(), right.rank());
            if (byRank != 0) {
                return byRank;
            }
            int byPriority = Integer.compare(right.definition().priority(),
                    left.definition().priority());
            if (byPriority != 0) {
                return byPriority;
            }
            return left.definition().id().compareTo(right.definition().id());
        });
        int bestRank = candidates.get(0).rank();
        if (countWithRank(candidates, bestRank) > 1
                && !everyCandidateWithRankMatchedExactId(candidates, bestRank)) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(0).definition());
    }

    /** How many candidates carry {@code rank}. */
    private static int countWithRank(List<Candidate> candidates, int rank) {
        int count = 0;
        for (Candidate candidate : candidates) {
            if (candidate.rank() == rank) {
                count++;
            }
        }
        return count;
    }

    /**
     * True when every candidate at {@code rank} also matched the structure id
     * exactly. Since rank {@code 0} is by construction the exact structure id
     * tier, this is exactly the "a rank {@code 0} tie keeps the deterministic
     * priority/id break" rule; every other best rank is refused when it is
     * shared. It preserves the historical exact-id tie-break for legacy callers
     * and for two definitions that claim the same structure id.
     */
    private static boolean everyCandidateWithRankMatchedExactId(List<Candidate> candidates,
                                                                int rank) {
        for (Candidate candidate : candidates) {
            if (candidate.rank() == rank && !candidate.exactId()) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code -1} when the definition is not a candidate at all (the trigger
     * filter rejected it, or a non-blank anchor entity vetoed it), otherwise its
     * match rank: {@code 0} exact structure id, {@code 1} anchor entity match,
     * {@code 2} structure tag, {@code 3} pure anchor match.
     */
    private static int rank(ResetDefinition definition, String structureId,
                            Set<String> tags, boolean anchorMatched,
                            String anchorEntityType) {
        boolean exact = false;
        boolean tag = false;
        for (StructureRef ref : definition.structures()) {
            if (ref == null) {
                continue;
            }
            if (ref.tag()) {
                if (!tags.isEmpty() && tags.contains(stripHash(ref.id()))) {
                    tag = true;
                }
            } else if (structureId != null && structureId.equals(ref.id())) {
                exact = true;
            }
        }
        boolean structureHit = exact || tag;
        boolean eligible;
        switch (definition.trigger()) {
            case IN_STRUCTURE:
                eligible = structureHit;
                break;
            case ANCHOR_BLOCK:
                eligible = anchorMatched;
                break;
            case EITHER:
            default:
                eligible = structureHit || anchorMatched;
                break;
        }
        if (!eligible) {
            return -1;
        }
        boolean entityConstraint = anchorEntityType != null && !anchorEntityType.isBlank();
        boolean entityMatch = entityConstraint
                && anchorEntityType.equals(definition.respawnBossEntity());
        // The stored anchor entity only vetoes definitions that do not claim the
        // entered structure id: an exact structure id match stays eligible (and
        // keeps rank 0) even when the definition respawns another boss.
        if (entityConstraint && !entityMatch && !exact) {
            return -1;
        }
        if (exact) {
            return 0;
        }
        if (entityMatch) {
            return 1;
        }
        if (tag) {
            return 2;
        }
        return 3;
    }

    /** True when the definition lists {@code structureId} as an exact (non-tag) reference. */
    private static boolean matchedExactStructureId(ResetDefinition definition, String structureId) {
        if (structureId == null) {
            return false;
        }
        for (StructureRef ref : definition.structures()) {
            if (ref != null && !ref.tag() && structureId.equals(ref.id())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> normalizeTags(Collection<String> structureTags) {
        Set<String> tags = new LinkedHashSet<>();
        if (structureTags == null) {
            return tags;
        }
        for (String tag : structureTags) {
            if (tag == null) {
                continue;
            }
            String value = stripHash(tag.trim());
            if (!value.isEmpty()) {
                tags.add(value);
            }
        }
        return tags;
    }

    private static String stripHash(String value) {
        return value.startsWith("#") ? value.substring(1) : value;
    }

    /**
     * A definition that survived the trigger filter and the anchor entity veto,
     * with its match rank and whether it also matched the structure id exactly.
     */
    private record Candidate(ResetDefinition definition, int rank, boolean exactId) {
    }
}
