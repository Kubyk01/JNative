package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.IrBuilder;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class InstructionHandlers {
    /**
     * Typed variants of {@code System.arraycopy}, keyed by the element type
     * of the two arrays.
     *
     * <p>The generic native implementation has to read the element size out
     * of both array headers at run time and compare the two before it can
     * compute how many bytes to move. When the static type of both operands
     * is known the size is a compile-time constant and the whole copy
     * collapses to a single {@code memmove}.</p>
     */
    private static final Map<Type, String> TYPED_ARRAYCOPY = Map.of(
        Type.BYTE, "__jnative_fn_java_lang_System_arraycopy__byte_bytes",
        Type.CHAR, "__jnative_fn_java_lang_System_arraycopy__char_chars",
        Type.INT,  "__jnative_fn_java_lang_System_arraycopy__int_ints"
    );

    private final IrBuilder builder;
    private final StackFrame frame;
    private final DependencyResolver resolver;

    public InstructionHandlers(IrBuilder builder, StackFrame frame,
                               DependencyResolver resolver) {
        this.builder = builder;
        this.frame = frame;
        this.resolver = resolver;
    }

    public void pushInt(int value) {
        frame.push(new Constant(Type.INT, value));
    }

    public void pushLong(long value) {
        frame.push(new Constant(Type.LONG, value));
    }

    public void pushFloat(float value) {
        frame.push(new Constant(Type.FLOAT, value));
    }

    public void pushDouble(double value) {
        frame.push(new Constant(Type.DOUBLE, value));
    }

    public void pushNull() {
        frame.push(new Constant(Type.NULL, null));
    }

    public void binaryOp(Opcode op) {
        Value right = frame.pop();
        Value left = frame.pop();
        Instruction inst = builder.addInstruction(op, left, right);
        frame.push(inst.getResult());
    }

    public void unaryNeg(Type type) {
        Value val = frame.pop();
        Constant zero = new Constant(type, type == Type.INT ? 0 :
            type == Type.LONG ? 0L :
                type == Type.FLOAT ? 0.0f : 0.0);
        Instruction inst = builder.addInstruction(Opcode.SUB, zero, val);
        frame.push(inst.getResult());
    }

    public void shiftOp(Opcode op) {
        Value amount = frame.pop();
        Value value  = frame.pop();
        Instruction inst = builder.addInstruction(op, value, amount);
        frame.push(inst.getResult());
    }

    public void cmpOp() {
        Value right = frame.pop();
        Value left = frame.pop();

        // lt = (left < right)   : i1
        Instruction lt = builder.addInstruction(Opcode.LT, left, right);
        // gt = (left > right)   : i1
        Instruction gt = builder.addInstruction(Opcode.GT, left, right);

        Instruction ltInt = builder.addInstruction(Opcode.CAST, Type.INT, lt.getResult());
        Instruction gtInt = builder.addInstruction(Opcode.CAST, Type.INT, gt.getResult());

        Instruction result = builder.addInstruction(
            Opcode.SUB, gtInt.getResult(), ltInt.getResult());

        frame.push(result.getResult());
    }

    public void convert() {
        Value val = frame.pop();
        Instruction conv = builder.addInstruction(Opcode.CAST, val);
        frame.push(conv.getResult());
    }

    public void convertTo(Type targetType) {
        Value val = frame.pop();
        Instruction conv = builder.addInstruction(Opcode.CAST, targetType, val);
        frame.push(conv.getResult());
    }

    public void returnValue() {
        Value val = frame.pop();
        builder.createReturn(val);
    }

    public void returnVoid() {
        builder.createReturn(null);
    }

    public void arrayLength() {
        Value arr = frame.pop();
        Instruction len = builder.addInstruction(Opcode.ARRAYLENGTH, arr);
        frame.push(len.getResult());
    }

    public void throwException() {
        Value ex = frame.pop();
        builder.createThrow(ex);
    }

    public void monitorEnter() {
        Value obj = frame.pop();
        builder.addInstruction(Opcode.MONITOR_ENTER, obj);
    }

    public void monitorExit() {
        Value obj = frame.pop();
        builder.addInstruction(Opcode.MONITOR_EXIT, obj);
    }

    public void newArray(int atype) {
        Value size = frame.pop();
        Type elemType = arrayTypeToIr(atype);
        Instruction inst = builder.addInstruction(Opcode.NEW_ARRAY, size,
            new Constant(Type.reference(elemType.toString()), elemType.toString()));
        frame.push(inst.getResult());
    }

    public void newObject(String type) {
        Instruction inst = builder.addInstruction(Opcode.NEW,
            new Constant(Type.reference(type), type));
        frame.push(inst.getResult());
    }

    public void anewArray(String type) {
        Value size = frame.pop();
        Instruction inst = builder.addInstruction(Opcode.NEW_ARRAY, size,
            new Constant(Type.reference(type), type));
        frame.push(inst.getResult());
    }

    public void checkCast(String type) {
        Value val = frame.pop();
        Instruction inst = builder.addInstruction(Opcode.CHECKCAST, val,
            new Constant(Type.reference(type), type));
        frame.push(inst.getResult());
    }

    public void instanceOf(String type) {
        Value val = frame.pop();
        Instruction inst = builder.addInstruction(Opcode.INSTANCEOF, val,
            new Constant(Type.reference(type), type));
        frame.push(inst.getResult());
    }

    public void arrayLoad() {
        Value index = frame.pop();
        Value array = frame.pop();
        Instruction inst = builder.addInstruction(Opcode.ALOAD, array, index);
        frame.push(inst.getResult());
    }

    public void arrayStore() {
        Value value = frame.pop();
        Value index = frame.pop();
        Value array = frame.pop();
        builder.addInstruction(Opcode.ASTORE, array, index, value);
    }

    public void multiNewArray(String desc, int dims) {
        List<Value> sizes = new ArrayList<>();
        for (int i = 0; i < dims; i++) {
            sizes.add(frame.pop());
        }
        Instruction inst = builder.addInstruction(Opcode.MULTI_NEW_ARRAY,
            new Constant(Type.reference(desc), desc));
        for (int i = sizes.size() - 1; i >= 0; i--) {
            inst.addOperand(sizes.get(i));
        }
        frame.push(inst.getResult());
    }

    private String resolveDeclaringOwner(String owner, String name) {
        if (owner == null || name == null) return owner;

        FieldNode field = resolver.getField(owner, name);
        if (field == null) return owner;

        String declaring = field.getOwner();
        if (declaring == null || declaring.isEmpty()) return owner;
        return declaring;
    }

    public void getField(String owner, String name) {
        Value obj = frame.pop();
        Type fieldType;
        FieldNode field = resolver.getField(owner, name);
        if (field != null) {
            fieldType = field.getType();
        } else {
            fieldType = Type.reference("java/lang/Object");
        }

        String declaringOwner = resolveDeclaringOwner(owner, name);
        String canonicalKey = declaringOwner + "." + name;

        Instruction inst = new Instruction(Opcode.GET_FIELD);
        inst.addOperand(obj);
        inst.addOperand(new Constant(Type.reference(canonicalKey), canonicalKey));
        Temporary tmp = builder.newTemporary(fieldType);
        inst.setResult(tmp);
        tmp.setDefiningInstruction(inst);
        builder.currentBlock().addInstruction(inst);
        frame.push(tmp);
    }

    public void putField(String owner, String name) {
        Value val = frame.pop();
        Value obj = frame.pop();

        String declaringOwner = resolveDeclaringOwner(owner, name);
        String canonicalKey = declaringOwner + "." + name;

        builder.addInstruction(Opcode.PUT_FIELD, obj,
            new Constant(Type.reference(canonicalKey), canonicalKey), val);
    }

    // ------------------------------------------------------------------
    //  Static field access
    // ------------------------------------------------------------------

    public void getStatic(String owner, String name) {
        Type fieldType;
        FieldNode field = resolver.getField(owner, name);
        if (field != null) {
            fieldType = field.getType();
        } else {
            fieldType = Type.reference("java/lang/Object");
        }

        String declaringOwner = resolveDeclaringOwner(owner, name);
        String canonicalKey = declaringOwner + "." + name;

        Instruction inst = new Instruction(Opcode.GET_STATIC);
        inst.addOperand(new Constant(Type.reference(canonicalKey), canonicalKey));
        Temporary tmp = builder.newTemporary(fieldType);
        inst.setResult(tmp);
        tmp.setDefiningInstruction(inst);
        builder.currentBlock().addInstruction(inst);
        frame.push(tmp);
    }

    public void putStatic(String owner, String name) {
        Value val = frame.pop();

        String declaringOwner = resolveDeclaringOwner(owner, name);
        String canonicalKey = declaringOwner + "." + name;

        builder.addInstruction(Opcode.PUT_STATIC,
            new Constant(Type.reference(canonicalKey), canonicalKey), val);
    }

    // ------------------------------------------------------------------
    //  Method calls
    // ------------------------------------------------------------------

    /**
     * Lowers one method-call bytecode into an IR call instruction.
     *
     * <p>The IR opcode is chosen from the <em>resolved</em> owner, not
     * from the bytecode opcode alone. That distinction matters because the
     * bytecode opcode and the class that actually declares the resolved
     * target can disagree:</p>
     *
     * <ul>
     *   <li>{@code INVOKEVIRTUAL} whose resolved target is a
     *       <em>default method</em> on an interface. The JVM's own
     *       vtable layout handles this transparently, but this emitter
     *       does not: the class vtable of the concrete receiver and the
     *       interface itable do not share slot numbering, so a
     *       {@code VIRTUAL_CALL} with an interface owner reads the
     *       receiver's class vtable at the interface's slot index and
     *       lands on an unrelated method or on an unresolved thunk.
     *       {@code INTERFACE_CALL} routes the dispatch through
     *       {@code __jnative_lookup_itable}, which finds the correct
     *       entry in the receiver's interface map.</li>
     *
     *   <li>{@code INVOKEINTERFACE} whose resolved target is declared on
     *       a class — the common case being a method inherited from
     *       {@code java/lang/Object} through an interface, or a covariant
     *       override whose erasure lives on the class. Here the correct
     *       opcode is {@code VIRTUAL_CALL}: the receiver has no itable
     *       entry for the interface in question, so interface dispatch
     *       would look up a table that does not exist.</li>
     * </ul>
     *
     * <p>Concrete failure the {@code INVOKEVIRTUAL -> interface} branch
     * closes: {@code sun.security.util.DisabledAlgorithmConstraints
     * .DenyAfterConstraint.<init>} contains
     * {@code INVOKEVIRTUAL java/time/ZonedDateTime.getSecond()I}, and
     * {@code getSecond()} is a default method on the interface
     * {@code java/time/chrono/ChronoZonedDateTime}. Without this branch
     * the emitter classified the call as {@code VIRTUAL_CALL} with owner
     * {@code java/time/chrono/ChronoZonedDateTime}, read the receiver's
     * class vtable at the interface's slot index, hit the null thunk
     * {@code __jnative_vtable_missing_java_time_ZonedDateTime_getSecond__I},
     * and aborted with {@code JNative unresolved vtable/itable slot}.</p>
     */
    public void callMethod(int opcode, String owner, String name, String desc, boolean polymorphic) {
        List<Type> paramTypes = TypeResolver.descToParamTypes(desc);
        Type retType = TypeResolver.descToReturnType(desc);
        int paramCount = paramTypes.size();

        List<Value> args = frame.popArgs(paramCount);

        Opcode irOpcode;
        Value receiver = null;
        irOpcode = switch (opcode) {
            case Opcodes.INVOKEVIRTUAL -> {
                receiver = frame.pop();
                // An INVOKEVIRTUAL whose resolved owner is an interface
                // means the target is a default method inherited from
                // that interface by the compile-time receiver class.
                // The JVM handles this transparently in its own vtable,
                // but this emitter does not: the class vtable of the
                // concrete receiver and the interface itable do not
                // share slot numbering, so a VIRTUAL_CALL with an
                // interface owner reads the receiver's class vtable at
                // the interface's slot index and lands on an unrelated
                // method or on an unresolved thunk.
                //
                // Concretely,
                //     sun.security.util.DisabledAlgorithmConstraints
                //         .DenyAfterConstraint.<init>
                // contains
                //     INVOKEVIRTUAL java/time/ZonedDateTime.getSecond()I
                // getSecond() is a default method on ChronoZonedDateTime;
                // the constant-pool owner is ZonedDateTime.
                // findMethodInHierarchy resolves the call to
                // ChronoZonedDateTime, and this method receives that
                // interface name as `owner`. Classifying the IR opcode
                // from the bytecode opcode alone would produce
                // VIRTUAL_CALL with an interface owner, and the emitter
                // would then read the receiver's class vtable at the
                // interface's own slot index — the wrong slot. Emitting
                // INTERFACE_CALL routes the dispatch through
                // __jnative_lookup_itable, which finds the correct entry
                // in the receiver's interface map and returns the itable
                // the interface layout was built for.
                ClassNode resolvedOwnerNode = resolver.getClassNode(owner);
                if (resolvedOwnerNode != null && resolvedOwnerNode.isInterface()) {
                    yield Opcode.INTERFACE_CALL;
                }
                yield Opcode.VIRTUAL_CALL;
            }
            case Opcodes.INVOKEINTERFACE -> {
                receiver = frame.pop();
                // An invokeinterface can resolve to a method whose actual
                // declaration lives on a class (typically java/lang/Object,
                // whose methods every interface inherits). In that case the
                // real dispatch is virtual — emit VIRTUAL_CALL, not
                // INTERFACE_CALL. Otherwise the codegen would look up an
                // itable that does not exist for a class.
                ClassNode ownerNode = resolver.getClassNode(owner);
                if (ownerNode != null && !ownerNode.isInterface()) {
                    yield Opcode.VIRTUAL_CALL;
                }
                yield Opcode.INTERFACE_CALL;
            }
            case Opcodes.INVOKESTATIC -> Opcode.STATIC_CALL;
            case Opcodes.INVOKESPECIAL -> {
                receiver = frame.pop();
                yield Opcode.SPECIAL_CALL;
            }
            default -> Opcode.CALL;
        };

        String callee = owner + "." + name + desc;

        // A System.arraycopy call whose two operands are statically known
        // to be arrays of the same primitive element type is routed to the
        // typed variant of the native method: the element size becomes a
        // compile-time constant, so the run-time size comparison and the
        // two header reads the generic implementation performs for it
        // disappear. Everything else keeps the generic path.
        String typedArraycopy = typedArraycopySymbol(opcode, owner, name, desc, args);
        if (typedArraycopy != null) {
            callee = typedArraycopy;
            declareTypedArraycopy(typedArraycopy);
        }

        Instruction callInst = new Instruction(irOpcode);
        callInst.setPolymorphicSignature(polymorphic);
        if (receiver != null) callInst.addOperand(receiver);
        callInst.addOperand(new Constant(Type.reference(callee), callee));
        for (Value arg : args) {
            callInst.addOperand(arg);
        }

        if (!retType.isVoid()) {
            Temporary tmp = builder.newTemporary(retType);
            callInst.setResult(tmp);
            tmp.setDefiningInstruction(callInst);
            builder.currentBlock().addInstruction(callInst);
            frame.push(tmp);
        } else {
            builder.currentBlock().addInstruction(callInst);
        }
    }

    /**
     * Returns the symbol of the typed {@code System.arraycopy} variant that
     * matches the static types of the two array operands, or {@code null}
     * when the call has to go through the generic implementation.
     *
     * <p>Only the case where <em>both</em> operands are statically arrays
     * of the same primitive element type is specialised. The JVM verifier
     * guarantees that such a value really is an array of that element type
     * at run time, and the native implementation re-checks the element size
     * against its array header before it copies anything.</p>
     */
    private String typedArraycopySymbol(int opcode, String owner, String name,
                                        String desc, List<Value> args) {
        if (opcode != Opcodes.INVOKESTATIC) return null;
        if (!"java/lang/System".equals(owner) || !"arraycopy".equals(name)) return null;
        if (!"(Ljava/lang/Object;ILjava/lang/Object;II)V".equals(desc)) return null;
        if (args == null || args.size() != 5) return null;

        Type srcElement = arrayElementType(args.get(0));
        if (srcElement == null) return null;
        Type destElement = arrayElementType(args.get(2));
        if (destElement == null) return null;
        if (!srcElement.equals(destElement)) return null;

        return TYPED_ARRAYCOPY.get(srcElement);
    }

    /**
     * The element type of an array-typed value, or {@code null} when the
     * value is not an array of a primitive element type.
     */
    private static Type arrayElementType(Value value) {
        if (value == null) return null;
        Type type = value.getType();
        if (type == null || !type.isArray()) return null;
        Type element = type.getElementType();
        return (element != null && element.isPrimitive()) ? element : null;
    }

    /**
     * Registers the declaration of a typed {@code System.arraycopy} variant
     * in the module.
     *
     * <p>The body lives in {@code jnative/lang/System.c}; the declaration is
     * what tells the emitter which parameter types the call site has to
     * convert its arguments to. Registering the function in the module (as
     * opposed to listing the symbol in the runtime prelude) keeps the
     * signature in one place: the prelude would emit a second, independent
     * declaration of the same symbol into the same file.</p>
     */
    private void declareTypedArraycopy(String symbol) {
        Module module = builder.getModule();
        if (module == null || module.getFunction(symbol) != null) return;

        Function func = new Function(symbol, Type.VOID);
        func.addParameter(new Parameter(Type.reference("java/lang/Object"), 0, 0));
        func.addParameter(new Parameter(Type.INT, 1, 1));
        func.addParameter(new Parameter(Type.reference("java/lang/Object"), 2, 2));
        func.addParameter(new Parameter(Type.INT, 3, 3));
        func.addParameter(new Parameter(Type.INT, 4, 4));
        module.addFunction(func);
    }

    private Type arrayTypeToIr(int atype) {
        return switch (atype) {
            case Opcodes.T_BOOLEAN -> Type.BOOLEAN;
            case Opcodes.T_BYTE -> Type.BYTE;
            case Opcodes.T_CHAR -> Type.CHAR;
            case Opcodes.T_SHORT -> Type.SHORT;
            case Opcodes.T_INT -> Type.INT;
            case Opcodes.T_LONG -> Type.LONG;
            case Opcodes.T_FLOAT -> Type.FLOAT;
            case Opcodes.T_DOUBLE -> Type.DOUBLE;
            default -> Type.UNKNOWN;
        };
    }

    public void loadCaughtException() {
        Instruction inst = new Instruction(Opcode.STATIC_CALL);
        inst.addOperand(new Constant(
            Type.reference("__jnative_get_exception_object"),
            "__jnative_get_exception_object"));
        Temporary tmp = builder.newTemporary(Type.reference("java/lang/Throwable"));
        inst.setResult(tmp);
        tmp.setDefiningInstruction(inst);
        builder.currentBlock().addInstruction(inst);
        frame.push(tmp);
    }
}