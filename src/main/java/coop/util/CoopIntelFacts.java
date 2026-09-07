package coop.util;

import java.util.Map;

/**
 * What one of the mod's own intel entries is worth reporting to the agent bridge's {@code intel}
 * verb, beyond the title and flags every entry has.
 *
 * <p><b>Why an interface rather than a switch in the bridge.</b> The bridge is deliberately not
 * allowed a parallel reader of anything (see {@code CoopAgentCommands}' class doc), and the numbers
 * that matter about an entry are the ones the entry itself renders. Keeping the answer next to the
 * class that owns the numbers means a field added to a page is one edit, not two, and the bridge
 * never has to reach into a UI class's internals to guess at them.
 *
 * <p><b>Total by contract.</b> An implementation must not throw and must not return null; the bridge
 * still wraps the call, but an intel page that cannot describe itself has to degrade to "no extras"
 * rather than take the dump down. Values must be JSON-primitive — string, number or boolean.
 */
public interface CoopIntelFacts {

    /**
     * The entry's key numbers, keyed by field name. Empty when there is nothing to add — never null.
     */
    Map<String, Object> intelFacts();
}
