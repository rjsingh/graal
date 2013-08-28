package com.edinburgh.parallel.opencl;

import java.util.*;
import java.util.concurrent.*;

import com.oracle.graal.nodes.*;

public final class ParallelMethods {

    @SuppressWarnings("unused") public static ConcurrentHashMap<String, StructuredGraph> methods = new ConcurrentHashMap<String, StructuredGraph>();
    public static Map<String, String> methodCodes = new HashMap<>();
}
