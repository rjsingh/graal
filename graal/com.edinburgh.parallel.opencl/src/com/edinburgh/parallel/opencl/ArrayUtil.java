package com.edinburgh.parallel.opencl;

import java.lang.reflect.*;

public class ArrayUtil {

    public static Object transformArray(Object srcArray) {
        if (srcArray == null || !srcArray.getClass().isArray())
            return null;

        int arraySize = ArrayUtil.getArraySize(srcArray);
        Object flatArray = TypeUtil.newArray(srcArray, arraySize);
        flattenArray(srcArray, flatArray, new int[]{0});

        return flatArray;
    }

    public static void flattenArray(Object srcArray, Object destArray, int[] index) {
        if (srcArray != null) {
            for (int i = 0; i < Array.getLength(srcArray); i++) {
                Object elem = Array.get(srcArray, i);
                if (elem != null) {
                    if (elem.getClass().isArray()) {
                        flattenArray(elem, destArray, index);
                    } else {
                        Array.set(destArray, index[0]++, elem);
                    }
                }
            }
        }
    }

    public static int getArraySize(Object a) {
        if (a == null) {
            return 0;
        }
        if (a.getClass().isArray()) {
            int count = 0;
            for (int i = 0; i < Array.getLength(a); i++) {
                int size = getArraySize(Array.get(a, i));
                if (size == -1) {
                    return Array.getLength(a);
                } else {
                    count += size;
                }
            }
            return count;
        } else {
            return -1;
        }
    }

    public static void rebuild(Object srcArray, Object destArray, int[] index) {
        if (srcArray != null) {
            for (int i = 0; i < Array.getLength(destArray); i++) {
                Object elem = Array.get(destArray, i);
                if (elem != null) {
                    if (elem.getClass().isArray()) {
                        rebuild(srcArray, elem, index);
                    } else {
                        Array.set(destArray, i, Array.get(srcArray, index[0]++));
                    }
                }
            }
        }
    }

    public static int getNumArrayDimensions(Object a) {
        if (a.getClass().isArray()) {
            return a.getClass().getName().length() - 1;
        }
        return 0;
    }
}
