package org.checkerframework.framework.util.typeinference.solver;

import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedTypeVariable;
import org.checkerframework.framework.util.typeinference.TypeArgInferenceUtil;
import org.checkerframework.framework.util.typeinference.solver.InferredValue.InferredTarget;
import org.checkerframework.framework.util.typeinference.solver.InferredValue.InferredType;
import org.checkerframework.framework.util.typeinference.solver.TargetConstraints.Equalities;
import org.checkerframework.javacutil.ErrorReporter;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVariable;

/**
 *  EqualitiesSolver infers type arguments for targets using the equality constraints in ConstraintMap.  When
 *  a type is inferred, it rewrites the remaining equality/supertype constraints
 */
public class EqualitiesSolver {
    private boolean dirty = false;

    /**
     * For each target,
     *     if there is one or more equality constraints involving concrete types that lets us infer a
     *       primary annotation in all qualifier hierarchies then infer a concrete type argument.
     *     else if there is one or more equality constraints involving other targets that lets us
     *       infer a primary annotation in all qualifier hierarchies then infer that type argument
     *       is the other type argument
     *
     *     if we have inferred either a concrete type or another target as type argument
     *        rewrite all of the constraints for the current target to instead use the inferred type/target
     *
     *
     * We do this iteratively until NO new inferred type argument is found
     *
     *
     * @param targets The list of type parameters for which we are inferring type arguments
     * @param constraintMap The set of constraints over the set of targets
     * @return A Map( {@code target -> inferred type or target })
     */
    public InferenceResult solveEqualities(Set<TypeVariable> targets, ConstraintMap constraintMap, AnnotatedTypeFactory typeFactory) {
        final InferenceResult solution = new InferenceResult();

        do {
            dirty = false;
            for (TypeVariable target : targets) {
                if (solution.containsKey(target)) {
                    continue;
                }

                Equalities equalities = constraintMap.getConstraints(target).equalities;

                InferredValue inferred = mergeConstraints(target, equalities, solution, constraintMap, typeFactory);
                if (inferred != null) {
                    if (inferred instanceof InferredType) {
                        rewriteWithInferredType(target, ((InferredType) inferred).type, constraintMap);
                    } else {
                        rewriteWithInferredTarget(target, ((InferredTarget) inferred).target, constraintMap, typeFactory);
                    }

                    solution.put(target, inferred);
                }
            }

        } while (dirty);

        solution.resolveChainedTargets();

        return solution;
    }

    /**
     * Let Ti be a target type parameter.
     * When we reach this method we have inferred an argument, Ai, for Ti
     *
     * However, there still may be constraints of the form Ti = Tj, Ti <: Tj, Tj <: Ti in the constraint map.  In this
     * case we need to replace Ti with the type.  That is, they become Ai = Tj, Ai <: Tj, and Tj <: Ai
     *
     * To do this, we find the TargetConstraints for Tj and add these constraints to the appropriate map
     * in TargetConstraints.  We can then clear the constraints for the current target since we have inferred a type.
     *
     * @param target The target for which we have inferred a concrete type argument
     * @param type the type inferred
     */
    private void rewriteWithInferredType(final TypeVariable target, final AnnotatedTypeMirror type, final ConstraintMap constraints) {

        final TargetConstraints targetRecord = constraints.getConstraints(target);
        final Map<TypeVariable, Set<AnnotationMirror>> equivalentTargets = targetRecord.equalities.targets;
        //each target that was equivalent to this one needs to be equivalent in the same hierarchies as the inferred type
        for (final Entry<TypeVariable, Set<AnnotationMirror>> eqEntry : equivalentTargets.entrySet()) {
            constraints.addTypeEqualities(eqEntry.getKey(), type, eqEntry.getValue());
        }

        for (TypeVariable otherTarget : constraints.getTargets()) {
            if (otherTarget != target) {
                final TargetConstraints record = constraints.getConstraints(otherTarget);

                //each target that was equivalent to this one needs to be equivalent in the same hierarchies as the inferred type
                final Set<AnnotationMirror> hierarchies = record.equalities.targets.get(target);
                if (hierarchies != null) {
                    record.equalities.targets.remove(target);
                    constraints.addTypeEqualities(otherTarget, type, hierarchies);
                }

                //otherTypes may have AnnotatedTypeVariables of type target, run substitution on these with type
                Map<AnnotatedTypeMirror, Set<AnnotationMirror>> toIterate = new LinkedHashMap<>(record.equalities.types);
                record.equalities.types.clear();
                for (AnnotatedTypeMirror otherType : toIterate.keySet()) {
                    final AnnotatedTypeMirror copy = TypeArgInferenceUtil.substitute(target, type, otherType);
                    final Set<AnnotationMirror> otherHierarchies = toIterate.get(otherType);
                    record.equalities.types.put(copy, otherHierarchies);
                }
            }
        }

        for (TypeVariable otherTarget : constraints.getTargets()) {
            if (otherTarget != target) {
                final TargetConstraints record = constraints.getConstraints(otherTarget);

                //each target that was equivalent to this one needs to be equivalent in the same hierarchies as the inferred type
                final Set<AnnotationMirror> hierarchies = record.supertypes.targets.get(target);
                if (hierarchies != null) {
                    record.supertypes.targets.remove(target);
                    constraints.addTypeEqualities(otherTarget, type, hierarchies);
                }

                //otherTypes may have AnnotatedTypeVariables of type target, run substitution on these with type
                Map<AnnotatedTypeMirror, Set<AnnotationMirror>> toIterate = new LinkedHashMap<>(record.supertypes.types);
                record.supertypes.types.clear();
                for (AnnotatedTypeMirror otherType : toIterate.keySet()) {
                    final AnnotatedTypeMirror copy = TypeArgInferenceUtil.substitute(target, type, otherType);
                    final Set<AnnotationMirror> otherHierarchies = toIterate.get(otherType);
                    record.supertypes.types.put(copy, otherHierarchies);
                }
            }
        }

        targetRecord.equalities.clear();
        targetRecord.supertypes.clear();
    }

    /**
     * Let Ti be a target type parameter.
     * When we reach this method we have inferred that Ti has the exact same argument as another target Tj
     *
     * Therefore, we want to stop solving for Ti and instead wait till we solve for Tj and use that result.
     *
     * Let ATM be any annotated type mirror and Tk be a target type parameter where k != i and k != j
     * Even though we've inferred Ti = Tj, there still may be constraints of the form Ti = ATM or Ti <: Tk
     * These constraints are still useful for inferring a argument for Ti/Tj.  So, we replace Ti in these
     * constraints with Tj and place those constraints in the TargetConstraints object for Tj.
     *
     * We then clear the constraints for Ti.
     *
     * @param target The target for which we know another target is exactly equal to this target
     * @param inferredTarget the other target inferred to be equal
     */
    private void rewriteWithInferredTarget(final TypeVariable target, final TypeVariable inferredTarget, final ConstraintMap constraints,
                                          final AnnotatedTypeFactory typeFactory) {
        final TargetConstraints targetRecord = constraints.getConstraints(target);
        final Map<AnnotatedTypeMirror, Set<AnnotationMirror>> equivalentTypes = targetRecord.equalities.types;
        final Map<AnnotatedTypeMirror, Set<AnnotationMirror>> supertypes = targetRecord.supertypes.types;

        //each type that was equivalent to this one needs to be equivalent in the same hierarchies to the inferred target
        for (final Entry<AnnotatedTypeMirror, Set<AnnotationMirror>> eqEntry : equivalentTypes.entrySet()) {
            constraints.addTypeEqualities(inferredTarget, eqEntry.getKey(), eqEntry.getValue());
        }

        for (final Entry<AnnotatedTypeMirror, Set<AnnotationMirror>> superEntry : supertypes.entrySet()) {
            constraints.addTypeSupertype(inferredTarget, superEntry.getKey(), superEntry.getValue());
        }

        for (TypeVariable otherTarget : constraints.getTargets()) {
            if (otherTarget != target && otherTarget != inferredTarget) {
                final TargetConstraints record = constraints.getConstraints(otherTarget);

                //each target that was equivalent to this one needs to be equivalent in the same hierarchies as the inferred target
                final Set<AnnotationMirror> hierarchies = record.equalities.targets.get(target);
                if (hierarchies != null) {
                    record.equalities.targets.remove(target);
                    constraints.addTargetEquality(otherTarget, inferredTarget, hierarchies);
                }

                //otherTypes may have AnnotatedTypeVariables of type target, run substitution on these with type
                Map<AnnotatedTypeMirror, Set<AnnotationMirror>> toIterate = new LinkedHashMap<>(record.equalities.types);
                record.equalities.types.clear();
                for (AnnotatedTypeMirror otherType : toIterate.keySet()) {
                    final AnnotatedTypeMirror copy = TypeArgInferenceUtil.substitute(target, createAnnotatedTypeVar(target, typeFactory), otherType);
                    final Set<AnnotationMirror> otherHierarchies = toIterate.get(otherType);
                    record.equalities.types.put(copy, otherHierarchies);
                }
            }
        }

        for (TypeVariable otherTarget : constraints.getTargets()) {
            if (otherTarget != target && otherTarget != inferredTarget) {
                final TargetConstraints record = constraints.getConstraints(otherTarget);

                final Set<AnnotationMirror> hierarchies = record.supertypes.targets.get(target);
                if (hierarchies != null) {
                    record.supertypes.targets.remove(target);
                    constraints.addTargetSupertype(otherTarget, inferredTarget, hierarchies);
                }

                //otherTypes may have AnnotatedTypeVariables of type target, run substitution on these with type
                Map<AnnotatedTypeMirror, Set<AnnotationMirror>> toIterate = new LinkedHashMap<>(record.supertypes.types);
                record.supertypes.types.clear();
                for (AnnotatedTypeMirror otherType : toIterate.keySet()) {
                    final AnnotatedTypeMirror copy = TypeArgInferenceUtil.substitute(target, createAnnotatedTypeVar(target, typeFactory), otherType);
                    final Set<AnnotationMirror> otherHierarchies = toIterate.get(otherType);
                    record.supertypes.types.put(copy, otherHierarchies);
                }
            }
        }

        targetRecord.equalities.clear();
        targetRecord.supertypes.clear();
    }

    /**
     * Creates a declaration AnnotatedTypeVariable for TypeVariable.
     */
    private AnnotatedTypeVariable createAnnotatedTypeVar(final TypeVariable typeVariable, final AnnotatedTypeFactory typeFactory) {
        return (AnnotatedTypeVariable) typeFactory.getAnnotatedType(typeVariable.asElement());
    }


    /**
     *
     * @param typesToHierarchies A mapping of (types -> hierarchies) that indicate that the argument being inferred
     *                           is equal to the types in each of the hierarchies
     * @param primaries A map (hierarchy -> annotation in hierarchy) where the annotation in hierarchy is equal to
     *                  the primary annotation on the argument being inferred
     * @param tops The set of top annotations in the qualifier hierarchy
     * @return A concrete type argument or null if there was not enough information to infer one
     */
    private InferredType mergeTypesAndPrimaries(
            Map<AnnotatedTypeMirror, Set<AnnotationMirror>> typesToHierarchies,
            Map<AnnotationMirror, AnnotationMirror> primaries,
            final Set<? extends AnnotationMirror> tops) {
        final Set<AnnotationMirror> missingAnnos = new HashSet<>(tops);

        Iterator<Entry<AnnotatedTypeMirror, Set<AnnotationMirror>>> entryIterator = typesToHierarchies.entrySet().iterator();
        if (!entryIterator.hasNext()) {
            ErrorReporter.errorAbort("Merging a list of empty types!");
        }

        final Entry<AnnotatedTypeMirror, Set<AnnotationMirror>> head = entryIterator.next();

        AnnotatedTypeMirror mergedType = head.getKey();
        missingAnnos.removeAll(head.getValue());

        //1. if there are multiple equality constraints in a ConstraintMap then the types better have
        //the same underlying type or Javac will complain and we won't be here.  When building ConstraintMaps
        //constraints involving AnnotatedTypeMirrors that are exactly equal are combined so there must be some
        //difference between two types being merged here.
        //2. Otherwise, we might have the same underlying type but conflicting annotations, then we take
        //the first set of annotations and show an error for the argument/return type that caused the second
        //differing constraint
        //3. Finally, we expect the following types to be involved in equality constraints:
        //AnnotatedDeclaredTypes, AnnotatedTypeVariables, and AnnotatedArrayTypes
        while (entryIterator.hasNext() && !missingAnnos.isEmpty()) {
            final Entry<AnnotatedTypeMirror, Set<AnnotationMirror>> current = entryIterator.next();
            final AnnotatedTypeMirror currentType = current.getKey();
            final Set<AnnotationMirror> currentHierarchies = current.getValue();

            Set<AnnotationMirror> found = new HashSet<>();
            for (AnnotationMirror top : missingAnnos) {
                if (currentHierarchies.contains(top)) {
                    final AnnotationMirror newAnno = currentType.getAnnotationInHierarchy(top);
                    if (newAnno != null) {
                        mergedType.replaceAnnotation(newAnno);
                        found.add(top);

                    } else if (mergedType.getKind() == TypeKind.TYPEVAR
                            && currentType.getUnderlyingType().equals(mergedType.getUnderlyingType())) {
                        //the options here are we are merging with the same typevar, in which case
                        //we can just remove the annotation from the missing list
                        found.add(top);

                    } else {
                        //otherwise the other type is missing an annotation
                        ErrorReporter.errorAbort("Missing annotation.\n"
                               + "\nmergedType="  + mergedType
                               + "\ncurrentType=" + currentType);

                    }
                }
            }

            missingAnnos.removeAll(found);
        }

        //add all the annotations from the primaries
        final HashSet<AnnotationMirror> foundHierarchies = new HashSet<>();
        for (final AnnotationMirror top : missingAnnos) {
            final AnnotationMirror anno = primaries.get(top);
            if (anno != null) {
                foundHierarchies.add(top);
                mergedType.replaceAnnotation(anno);
            }
        }

        typesToHierarchies.clear();


        if (missingAnnos.isEmpty()) {
            return new InferredType(mergedType);
        }

        //TODO: we probably can do more with this information than just putting it back into the
        //TODO: ConstraintMap (which is what's happening here)
        final Set<AnnotationMirror> hierarchies = new HashSet<>(tops);
        hierarchies.removeAll(missingAnnos);
        typesToHierarchies.put(mergedType, hierarchies);

        return null;
    }

    public InferredValue mergeConstraints(final TypeVariable target, final Equalities equalities,
                                          final InferenceResult solution, ConstraintMap constraintMap,
                                          AnnotatedTypeFactory typeFactory) {
        final Set<? extends AnnotationMirror> tops = typeFactory.getQualifierHierarchy().getTopAnnotations();
        InferredValue inferred = null;
        if (!equalities.types.isEmpty()) {
            inferred = mergeTypesAndPrimaries(equalities.types, equalities.primaries, tops);
        }

        if (inferred != null) {
            return inferred;
        } //else


        //We did not have enough information to infer an annotation in all hierarchies for one concrete type.
        //However, we have a "partial solution", one in which we know the type in some but not all qualifier hierarchies
        //Update our set of constraints with this information
        dirty |= updateTargetsWithPartiallyInferredType(equalities, constraintMap, typeFactory);
        inferred = findEqualTarget(equalities, tops);

        return inferred;
    }

    //If we determined that this target T1 is equal to a type ATM in hierarchies @A,@B,@C
    //for each of those hierarchies, if a target is equal to T1 in that hierarchy it is also equal to ATM
    // e.g.
    //   if : T1 == @A @B @C ATM in only the A,B hierarchies
    //    and T1 == T2 only in @A hierarchy
    //
    //   then T2 == @A @B @C only in the @A hierarchy
    //
    public boolean updateTargetsWithPartiallyInferredType( final Equalities equalities, ConstraintMap constraintMap,
                                                           AnnotatedTypeFactory typeFactory) {

        boolean updated = false;

        if (!equalities.types.isEmpty()) {
            if (equalities.types.size() != 1) {
                ErrorReporter.errorAbort("Equalities should have at most 1 constraint.");
            }

            Entry<AnnotatedTypeMirror, Set<AnnotationMirror>> remainingTypeEquality;
            remainingTypeEquality = equalities.types.entrySet().iterator().next();

            final AnnotatedTypeMirror remainingType = remainingTypeEquality.getKey();
            final Set<AnnotationMirror> remainingHierarchies = remainingTypeEquality.getValue();

            //update targets
            for (Map.Entry<TypeVariable, Set<AnnotationMirror>> targetToHierarchies  : equalities.targets.entrySet()) {
                final TypeVariable equalTarget = targetToHierarchies.getKey();
                final Set<AnnotationMirror> hierarchies = targetToHierarchies.getValue();

                final Set<AnnotationMirror> equalTypeHierarchies = new HashSet<>(remainingHierarchies);
                equalTypeHierarchies.retainAll(hierarchies);

                final Map<AnnotatedTypeMirror, Set<AnnotationMirror>> otherTargetsEqualTypes =
                        constraintMap.getConstraints(equalTarget).equalities.types;

                Set<AnnotationMirror> equalHierarchies = otherTargetsEqualTypes.get(remainingType);
                if (equalHierarchies == null) {
                    equalHierarchies = new HashSet<>(equalTypeHierarchies);
                    otherTargetsEqualTypes.put(remainingType, equalHierarchies);
                    updated = true;

                } else {
                    final int size = equalHierarchies.size();
                    equalHierarchies.addAll(equalTypeHierarchies);
                    updated = size == equalHierarchies.size();
                }
            }
        }

        return updated;
    }

    /**
     * Attempt to find a target which is equal to this target.
     * @return a target equal to this target in all hierarchies, or null
     */
    public InferredTarget findEqualTarget(final Equalities equalities,  Set<? extends AnnotationMirror> tops) {
        for (Map.Entry<TypeVariable, Set<AnnotationMirror>> targetToHierarchies  : equalities.targets.entrySet()) {
            final TypeVariable equalTarget = targetToHierarchies.getKey();
            final Set<AnnotationMirror> hierarchies = targetToHierarchies.getValue();

            //Now see if target is equal to equalTarget in all hierarchies
            boolean targetIsEqualInAllHierarchies = hierarchies.size() == tops.size();
            if (targetIsEqualInAllHierarchies) {
                return new InferredTarget(equalTarget, new HashSet<AnnotationMirror>());

            } else {
                //annos in primaries that are not covered by the target's list of equal hierarchies
                final Set<AnnotationMirror> requiredPrimaries = new HashSet<AnnotationMirror>(equalities.primaries.keySet());
                requiredPrimaries.removeAll(hierarchies);

                boolean typeWithPrimariesIsEqual = (requiredPrimaries.size() + hierarchies.size()) == tops.size();
                if (typeWithPrimariesIsEqual) {
                    return new InferredTarget(equalTarget, requiredPrimaries);
                }
            }
        }

        return null;
    }
}
