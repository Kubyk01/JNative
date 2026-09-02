package io.github.kubyk01.domain.ir;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
public class ResolvedCall {
    public enum Type { LAMBDA, CONCAT, DIRECT, LAZY, UNSUPPORTED }
    private final Type type;
    private final String lambdaStructName;
    private final String lambdaVtableName;
    private final String lambdaAdaptorName;
    private final String interfaceMethodSig;
    private final String concatFormat;
    private final String directMethodName;
    private final String bootstrapDataGlobal;
    private final List<io.github.kubyk01.domain.ir.Type> capturedTypes;

    private ResolvedCall(Type type, String lambdaStruct, String lambdaVtable, String lambdaAdaptor,
                         String ifaceSig, String concatFormat, String directName, String bootstrapData,
                         List<io.github.kubyk01.domain.ir.Type> capturedTypes) {
        this.type = type;
        this.lambdaStructName = lambdaStruct;
        this.lambdaVtableName = lambdaVtable;
        this.lambdaAdaptorName = lambdaAdaptor;
        this.interfaceMethodSig = ifaceSig;
        this.concatFormat = concatFormat;
        this.directMethodName = directName;
        this.bootstrapDataGlobal = bootstrapData;
        this.capturedTypes = capturedTypes != null ? new ArrayList<>(capturedTypes) : Collections.emptyList();
    }

    public static ResolvedCall lambda(String lambdaId, String interfaceMethodSig,
                                      List<io.github.kubyk01.domain.ir.Type> capturedTypes) {
        return new ResolvedCall(Type.LAMBDA, lambdaId, null, null, interfaceMethodSig,
                                null, null, null, capturedTypes);
    }
    public static ResolvedCall concat(String format) {
        return new ResolvedCall(Type.CONCAT, null, null, null, null, format, null, null, null);
    }
    public static ResolvedCall direct(String methodName) {
        return new ResolvedCall(Type.DIRECT, null, null, null, null, null, methodName, null, null);
    }
    public static ResolvedCall lazy(String dataGlobal) {
        return new ResolvedCall(Type.LAZY, null, null, null, null, null, null, dataGlobal, null);
    }
    public static ResolvedCall unsupported() {
        return new ResolvedCall(Type.UNSUPPORTED, null, null, null, null, null, null, null, null);
    }

}
