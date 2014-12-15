package org.checkerframework.framework.source;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Log;

/**
 * An aggregate checker that packages multiple checkers together.  The
 * resulting checker invokes the component checkers in turn on the processed
 * files.
 * <p>
 *
 * There is no communication, interaction, or cooperation between the
 * component checkers, even to the extent of being able to read one
 * another's qualifiers.  An aggregate checker is merely shorthand to
 * invoke a sequence of checkers.
 * <p>
 *
 * This class delegates {@code AbstractTypeProcessor} responsibilities to each
 * component checker.
 * <p>
 *
 * Checker writers need to subclass this class and only override
 * {@link #getSupportedCheckers()} to indicate the classes of the checkers
 * to be bundled.
 */
public abstract class AggregateChecker extends SourceChecker {

    protected final List<SourceChecker> checkers;

    /**
     * Returns the list of supported checkers to be run together.
     * Subclasses need to override this method.
     */
    protected abstract Collection<Class<? extends SourceChecker>> getSupportedCheckers();

    public AggregateChecker() {
        Collection<Class<? extends SourceChecker>> checkerClasses = getSupportedCheckers();

        checkers = new ArrayList<SourceChecker>(checkerClasses.size());
        for (Class<? extends SourceChecker> checkerClass : checkerClasses) {
            try {
                SourceChecker instance = checkerClass.newInstance();
                checkers.add(instance);
            } catch (Exception e) {
                message(Kind.ERROR, "Couldn't instantiate an instance of " + checkerClass);
            }
        }
    }

    /** processingEnv needs to be set on each checker since
        we are not calling init on the checker, which leaves
        it null.
        If one of checkers is an AggregateChecker, it's
        visitors will try use checker's processing env which
        should not be null.
    **/
    @Override
    protected void setProcessingEnvironment(ProcessingEnvironment env) {
        super.setProcessingEnvironment(env);
        for (SourceChecker checker : checkers) {
            checker.setProcessingEnvironment(env);
        }
    }

    @Override
    public void initChecker() {
        // No need to call super, it might result in reflective instantiations
        // of visitor/factory classes.
        // super.initChecker();
        // To prevent the warning that initChecker wasn't called.
        messager = processingEnv.getMessager();

        // first initialize all checkers
        initializeCheckers();
        // then share options as necessary
        for (SourceChecker checker : checkers) {
            // We need to add all options that are activated for the aggregate to
            // the individual checkers.
            checker.addOptions(super.getOptions());
            // Each checker should "support" all possible lint options - otherwise
            // subchecker A would complain about a lint option for subchecker B.
            checker.setSupportedLintOptions(this.getSupportedLintOptions());
        }
        allCheckersInited = true;
    }

    protected void initializeCheckers() {
        for (SourceChecker checker : checkers) {
            checker.initChecker();
        }
    }

    // Whether all checkers were successfully initialized.
    private boolean allCheckersInited = false;

    // AbstractTypeProcessor delegation
    @Override
    public final void typeProcess(TypeElement element, TreePath tree) {
        Context context = ((JavacProcessingEnvironment)processingEnv).getContext();
        Log log = Log.instance(context);
        if (log.nerrors > this.errsOnLastExit) {
            // If there is a Java error, do not perform any
            // of the component type checks, but come back
            // for the next compilation unit.
            this.errsOnLastExit = log.nerrors;
            return;
        }
        if (!allCheckersInited) {
            // If there was an initialization problem, an
            // error was already output. Just quit.
            return;
        }
        for (SourceChecker checker : checkers) {
            checker.errsOnLastExit = this.errsOnLastExit;
            checker.typeProcess(element, tree);
            this.errsOnLastExit = checker.errsOnLastExit;
        }
    }

    @Override
    public void typeProcessingOver() {
        for (SourceChecker checker : checkers) {
            checker.typeProcessingOver();
        }
    }

    @Override
    public final Set<String> getSupportedOptions() {
        Set<String> options = new HashSet<String>();
        for (SourceChecker checker : checkers) {
            options.addAll(checker.getSupportedOptions());
        }
        options.addAll(expandCFOptions(Arrays.asList(this.getClass()), options.toArray(new String[0])));
        return options;
    }

    @Override
    public final Map<String, String> getOptions() {
        Map<String, String> options = new HashMap<String, String>(super.getOptions());
        for (SourceChecker checker : checkers) {
            options.putAll(checker.getOptions());
        }
        return options;
    }

    @Override
    public final Set<String> getSupportedLintOptions() {
        Set<String> lints = new HashSet<String>();
        for (SourceChecker checker : checkers) {
            lints.addAll(checker.getSupportedLintOptions());
        }
        return lints;
    }

    @Override
    protected SourceVisitor<?, ?> createSourceVisitor() {
        return new AggregateVisitor(this);
    }

    // TODO some methods in a component checker should behave differently if they
    // are part of an aggregate, e.g. getSuppressWarningKeys should additionally
    // return the name of the aggregate checker.
    // We could add a query method in SourceChecker that refers to the aggregate, if present.
    // At the moment, all the component checkers manually need to add the name of the aggregate.
}

// TODO: change to independent compound visitor
class AggregateVisitor extends SourceVisitor<Void, Void> {

    protected List<SourceVisitor<?, ?>> visitors;

    public AggregateVisitor(AggregateChecker checker) {
        super(checker);
        ListBuffer<SourceVisitor<?, ?>> visitorsLB = new ListBuffer<SourceVisitor<?, ?>>();
        for (SourceChecker achecker : checker.checkers) {
            visitorsLB.add(achecker.createSourceVisitor());
        }
        visitors = visitorsLB.toList();
    }

    @Override
    public void setRoot(CompilationUnitTree root) {
        for (SourceVisitor<?, ?> avisitor : visitors) {
            avisitor.setRoot(root);
        }
    }

    @Override
    public void visit(TreePath path) {
        for (SourceVisitor<?, ?> avisitor : visitors) {
            avisitor.visit(path);
        }
    }
}
