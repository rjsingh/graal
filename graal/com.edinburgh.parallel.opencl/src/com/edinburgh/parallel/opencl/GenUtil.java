package com.edinburgh.parallel.opencl;

import java.util.*;

import com.oracle.graal.loop.*;

public class GenUtil {

    public static String newVariableName(String current) {
        return current.replaceAll("\\*|\\.", "");
    }

    public static List<InductionVariable> getInductionVariables(LoopEx loop) {
        List<InductionVariable> ret = new ArrayList<>();
        return null;
    }
}
