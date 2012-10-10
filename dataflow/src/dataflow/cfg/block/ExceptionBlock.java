package dataflow.cfg.block;

import java.util.Map;

import javax.lang.model.type.TypeMirror;

import dataflow.cfg.node.Node;

/**
 * Represents a basic block that contains exactly one {@link Node} which can
 * throw an exception. This block has exactly one non-exceptional successor, and
 * one or more exceptional successors.
 * 
 * <p>
 * 
 * The following invariant holds.
 * 
 * <pre>
 * getNode().getBlock() == this
 * </pre>
 * 
 * @author Stefan Heule
 * 
 */
public interface ExceptionBlock extends SingleSuccessorBlock {

    /**
     * @return The node of this block.
     */
    Node getNode();

    /**
     * @return The list of exceptional successor blocks as an unmodifiable map.
     */
    Map<TypeMirror, Block> getExceptionalSuccessors();

}