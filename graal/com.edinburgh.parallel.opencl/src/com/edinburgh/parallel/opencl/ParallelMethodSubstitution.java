package com.edinburgh.parallel.opencl;

import com.oracle.graal.api.replacements.*;
import com.oracle.graal.nodes.*;

@ClassSubstitution(className = "Main", optional = true)
public class ParallelMethodSubstitution {

    @MethodSubstitution(forced = true)
    public static void testMethod(int[] p0, int[] p1) {
        Object[] params = new Object[2];
        params[0] = p0;
        params[1] = p1;
        System.out.println("p0 name: " + params[0].getClass().getName());
        StructuredGraph sg = ParallelMethods.methods.get("ParallelMain.testMethod");
        ParallelUtil.run(sg, params);
    }
}
