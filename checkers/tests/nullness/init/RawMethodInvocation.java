import checkers.initialization.quals.UnknownInitialization;
import checkers.nullness.quals.EnsuresNonNull;
import checkers.nullness.quals.*;
import java.util.*;
@checkers.quals.DefaultQualifier(Nullable.class)
class RawMethodInvocation {
  @NonNull String a;
  @NonNull String b;

  RawMethodInvocation(boolean constructor_inits_a) {
    a = "";
    init_b();
  }

  @EnsuresNonNull("b")
  void init_b(@Raw @UnknownInitialization RawMethodInvocation this) {
    b = "";
  }

  //:: error: (initialization.fields.uninitialized)
  RawMethodInvocation(Byte constructor_inits_b) {
    init_b();
  }

  //:: error: (initialization.fields.uninitialized)
  RawMethodInvocation(byte constructor_inits_b) {
    b = "";
    init_b();
  }


  RawMethodInvocation(int constructor_inits_none) {
    init_ab();
  }

  @EnsuresNonNull({"a", "b"})
  void init_ab(@Raw @UnknownInitialization RawMethodInvocation this) {
    a = "";
    b = "";
  }

  RawMethodInvocation(long constructor_escapes_raw) {
    a = "";
    //:: error: (method.invocation.invalid)
    nonRawMethod();
    b = "";
  }

  void nonRawMethod() { }
}