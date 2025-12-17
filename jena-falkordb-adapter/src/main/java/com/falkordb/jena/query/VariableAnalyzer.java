package com.falkordb.jena.query;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.BasicPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Analyzer for SPARQL query variables to determine their grounding rules.
 *
 * <p>This analyzer examines a Basic Graph Pattern (BGP) to determine which variables
 * represent nodes (resources) vs. which could be attributes (literals). The key insight
 * is that if a variable appears as the subject (first element) of any triple, it MUST
 * be a node/resource, not an attribute.</p>
 *
 * <h2>Grounding Rules:</h2>
 * <ul>
 *   <li><strong>Node Variable</strong>: Appears as subject in at least one triple pattern</li>
 *   <li><strong>Ambiguous Variable</strong>: Only appears as object, could be node or literal</li>
 *   <li><strong>Predicate Variable</strong>: Appears as predicate in triple pattern</li>
 * </ul>
 *
 * <h2>Example Analysis:</h2>
 * <pre>{@code
 * // Pattern: ?person foaf:name ?name . ?person foaf:knows ?friend . ?friend foaf:age ?age .
 * //
 * // Analysis results:
 * // - ?person: NODE (appears as subject)
 * // - ?friend: NODE (appears as subject)
 * // - ?name: AMBIGUOUS (only as object, not used as subject)
 * // - ?age: AMBIGUOUS (only as object, not used as subject)
 * }</pre>
 *
 * <h2>Usage:</h2>
 * <pre>{@code
 * BasicPattern bgp = ...; // Your SPARQL pattern
 * AnalysisResult result = VariableAnalyzer.analyze(bgp);
 * 
 * if (result.isNodeVariable("person")) {
 *     // Generate relationship match
 * } else if (result.isAmbiguousVariable("name")) {
 *     // May need UNION for property or relationship
 * }
 * }</pre>
 */
public final class VariableAnalyzer {

    /** Logger instance. */
    private static final Logger LOGGER = LoggerFactory.getLogger(VariableAnalyzer.class);

    /**
     * Represents the type/grounding of a variable in a query pattern.
     */
    public enum VariableType {
        /**
         * Variable appears as subject - definitively a node/resource.
         */
        NODE,
        
        /**
         * Variable only appears as object - could be node or literal.
         */
        AMBIGUOUS,
        
        /**
         * Variable appears as predicate.
         */
        PREDICATE
    }

    /**
     * Result of analyzing a Basic Graph Pattern.
     *
     * @param bgp the analyzed pattern
     * @param variableTypes mapping from variable name to its type
     * @param subjectVariables set of variables that appear as subjects
     * @param objectVariables set of variables that appear as objects
     * @param predicateVariables set of variables that appear as predicates
     */
    public record AnalysisResult(
            BasicPattern bgp,
            Map<String, VariableType> variableTypes,
            Set<String> subjectVariables,
            Set<String> objectVariables,
            Set<String> predicateVariables) {

        /**
         * Check if a variable represents a node/resource.
         *
         * @param varName the variable name
         * @return true if the variable is definitely a node
         */
        public boolean isNodeVariable(final String varName) {
            return variableTypes.get(varName) == VariableType.NODE;
        }

        /**
         * Check if a variable is ambiguous (could be node or literal).
         *
         * @param varName the variable name
         * @return true if the variable's type is ambiguous
         */
        public boolean isAmbiguousVariable(final String varName) {
            return variableTypes.get(varName) == VariableType.AMBIGUOUS;
        }

        /**
         * Check if a variable represents a predicate.
         *
         * @param varName the variable name
         * @return true if the variable is a predicate
         */
        public boolean isPredicateVariable(final String varName) {
            return variableTypes.get(varName) == VariableType.PREDICATE;
        }

        /**
         * Get all variables in the pattern.
         *
         * @return set of all variable names
         */
        public Set<String> getAllVariables() {
            return variableTypes.keySet();
        }

        /**
         * Get variables that are definitely nodes.
         *
         * @return set of node variable names
         */
        public Set<String> getNodeVariables() {
            Set<String> nodes = new HashSet<>();
            for (Map.Entry<String, VariableType> entry : variableTypes.entrySet()) {
                if (entry.getValue() == VariableType.NODE) {
                    nodes.add(entry.getKey());
                }
            }
            return nodes;
        }

        /**
         * Get variables that are ambiguous.
         *
         * @return set of ambiguous variable names
         */
        public Set<String> getAmbiguousVariables() {
            Set<String> ambiguous = new HashSet<>();
            for (Map.Entry<String, VariableType> entry : variableTypes.entrySet()) {
                if (entry.getValue() == VariableType.AMBIGUOUS) {
                    ambiguous.add(entry.getKey());
                }
            }
            return ambiguous;
        }
    }

    /** Private constructor to prevent instantiation. */
    private VariableAnalyzer() {
        // Utility class
    }

    /**
     * Analyze a Basic Graph Pattern to determine variable grounding rules.
     *
     * <p>This method examines all triples in the BGP and classifies each variable
     * according to where it appears:</p>
     * <ul>
     *   <li>If a variable appears as a subject, it's a NODE</li>
     *   <li>If a variable appears as a predicate, it's a PREDICATE</li>
     *   <li>If a variable only appears as an object and never as a subject, it's AMBIGUOUS</li>
     * </ul>
     *
     * @param bgp the basic graph pattern to analyze
     * @return analysis result containing variable types and position information
     * @throws IllegalArgumentException if bgp is null or empty
     */
    public static AnalysisResult analyze(final BasicPattern bgp) {
        if (bgp == null || bgp.isEmpty()) {
            throw new IllegalArgumentException("BGP cannot be null or empty");
        }

        LOGGER.debug("Analyzing BGP with {} triples", bgp.size());

        // Track where each variable appears
        Set<String> subjectVariables = new HashSet<>();
        Set<String> objectVariables = new HashSet<>();
        Set<String> predicateVariables = new HashSet<>();

        // Analyze all triples
        List<Triple> triples = bgp.getList();
        for (Triple triple : triples) {
            Node subject = triple.getSubject();
            Node predicate = triple.getPredicate();
            Node object = triple.getObject();

            // Track subject variables
            if (subject.isVariable()) {
                String varName = subject.getName();
                subjectVariables.add(varName);
                LOGGER.trace("Variable {} appears as subject in: {}", varName, triple);
            }

            // Track predicate variables
            if (predicate.isVariable()) {
                String varName = predicate.getName();
                predicateVariables.add(varName);
                LOGGER.trace("Variable {} appears as predicate in: {}", varName, triple);
            }

            // Track object variables
            if (object.isVariable()) {
                String varName = object.getName();
                objectVariables.add(varName);
                LOGGER.trace("Variable {} appears as object in: {}", varName, triple);
            }
        }

        // Determine variable types based on analysis
        Map<String, VariableType> variableTypes = new HashMap<>();

        // All subject variables are definitively nodes
        for (String varName : subjectVariables) {
            variableTypes.put(varName, VariableType.NODE);
            LOGGER.debug("Variable {} classified as NODE (appears as subject)", varName);
        }

        // Predicate variables
        for (String varName : predicateVariables) {
            if (!variableTypes.containsKey(varName)) {
                variableTypes.put(varName, VariableType.PREDICATE);
                LOGGER.debug("Variable {} classified as PREDICATE", varName);
            }
        }

        // Object-only variables are ambiguous
        for (String varName : objectVariables) {
            if (!variableTypes.containsKey(varName)) {
                // Only appears as object, never as subject - ambiguous
                variableTypes.put(varName, VariableType.AMBIGUOUS);
                LOGGER.debug("Variable {} classified as AMBIGUOUS (only appears as object)", varName);
            }
        }

        LOGGER.debug("Analysis complete: {} NODE, {} AMBIGUOUS, {} PREDICATE",
                subjectVariables.size(),
                variableTypes.values().stream().filter(t -> t == VariableType.AMBIGUOUS).count(),
                predicateVariables.size());

        return new AnalysisResult(
                bgp,
                variableTypes,
                subjectVariables,
                objectVariables,
                predicateVariables);
    }

    /**
     * Analyze and check if pushdown is possible for a BGP.
     *
     * <p>Pushdown is possible if:</p>
     * <ul>
     *   <li>All ambiguous variables can be handled (via UNION if needed)</li>
     *   <li>Pattern doesn't have unsupported complexity</li>
     * </ul>
     *
     * @param bgp the basic graph pattern to analyze
     * @return true if pushdown optimization can be applied
     */
    public static boolean canPushdown(final BasicPattern bgp) {
        if (bgp == null || bgp.isEmpty()) {
            return false;
        }

        try {
            AnalysisResult result = analyze(bgp);
            
            // Currently, we can handle any pattern where we can distinguish
            // nodes from potential literals using the analysis
            // In the future, we might add more complex restrictions here
            
            LOGGER.debug("BGP can be pushed down: {} node vars, {} ambiguous vars",
                    result.getNodeVariables().size(),
                    result.getAmbiguousVariables().size());
            
            return true;
        } catch (Exception e) {
            LOGGER.warn("Error analyzing BGP for pushdown: {}", e.getMessage());
            return false;
        }
    }
}
