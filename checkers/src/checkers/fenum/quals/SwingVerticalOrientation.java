package checkers.fenum.quals;

import java.lang.annotation.*;

import checkers.quals.*;

/**
 * @author wmdietl
 * @checker_framework_manual #fenum-checker Fake Enum Checker
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
@TypeQualifier
@SubtypeOf(SwingBoxOrientation.class)
public @interface SwingVerticalOrientation {}