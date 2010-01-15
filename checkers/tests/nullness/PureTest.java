import checkers.nullness.quals.*;

class PureTest {
    @Pure @Nullable Object puremethod(@Nullable Object a) {
        return a;
    }

    public void test() {
        //:: (dereference.of.nullable)
        puremethod(null).toString();

        if (puremethod(null) == null) {
            //:: (dereference.of.nullable)
            puremethod(null).toString();
        }

        if (puremethod("m") != null) {
            puremethod("m").toString();
        }

        if (puremethod("m") != null) {
            //:: (dereference.of.nullable)
            puremethod(null).toString();
        }

        if (puremethod("m") != null) {
            //:: (dereference.of.nullable)
            puremethod("n").toString();
        }

        Object x = new Object();

        if (puremethod(x) == null) {
            return;
        }

        puremethod(x).toString();
        //:: (dereference.of.nullable)
        puremethod(x).toString();

        x = new Object();
        //:: (dereference.of.nullable)
        puremethod("n").toString();

    }

    public @Pure @Nullable Object getSuperclass() {
        return null;
    }

    static void shortCircuitAnd(PureTest pt) {
        if ((pt.getSuperclass() != null)
            && pt.getSuperclass().toString().equals("java.lang.Enum")) {
            // empty body
        }
    }

    static void shortCircuitOr(PureTest pt) {
        if ((pt.getSuperclass() == null)
            ||  pt.getSuperclass().toString().equals("java.lang.Enum")) {
            // empty body
        }
    }

    static void testInstanceof(PureTest pt) {
        if (pt.getSuperclass() instanceof Object) {
            return;
        }
        pt.getSuperclass().toString();
    }

}
