package com.geminispoofer;

import java.util.List;

/**
 * Data record holding relationship context ready for prompt injection.
 *
 * @param formattedContext  natural-language context string (2-4 sentences max)
 * @param knownEntities     entities the bot has relationships with
 * @param topicsWithPresent topics shared with currently present entities
 */
public record RelationshipContext(
    String formattedContext,
    List<String> knownEntities,
    List<String> topicsWithPresent
) {
}
