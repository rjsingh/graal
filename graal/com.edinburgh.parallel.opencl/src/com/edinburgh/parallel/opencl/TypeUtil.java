package com.edinburgh.parallel.opencl;

import org.jocl.*;

public class TypeUtil {

    public static int getTypeSizeForCL(Object obj) {
        if (obj.getClass().equals(Integer.class)) {
            return Sizeof.cl_int;
        }
        if (obj.getClass().getName().contains("I")) {
            return Sizeof.cl_int;
        }
        if (obj.getClass().getName().contains("F")) {
            return Sizeof.cl_float;
        }
        if (obj.getClass().getName().contains("C")) {
            return Sizeof.cl_char;
        }
        if (obj.getClass().getName().contains("J")) {
            return Sizeof.cl_long;
        }
        if (obj.getClass().getName().contains("S")) {
            return Sizeof.cl_short;
        }
        if (obj.getClass().getName().contains("B")) { // byte
            return Sizeof.cl_char;
        }
        if (obj.getClass().getName().contains("Z")) { // boolean
            return Sizeof.cl_char;
        }
        if (obj.getClass().getName().contains("D")) {
            return Sizeof.cl_double;
        }
        return -1; // UNKNOWN TYPE.
    }

    public static Object newArray(Object obj, int size) {
        if (obj.getClass().equals(Integer.class)) {
            return new int[size];
        }
        if (obj.getClass().getName().contains("I")) {
            return new int[size];
        }
        if (obj.getClass().getName().contains("F")) {
            return new float[size];
        }
        if (obj.getClass().getName().contains("C")) {
            return new char[size];
        }
        if (obj.getClass().getName().contains("J")) {
            return new long[size];
        }
        if (obj.getClass().getName().contains("S")) {
            return new short[size];
        }
        if (obj.getClass().getName().contains("B")) { // byte
            return new byte[size];
        }
        if (obj.getClass().getName().contains("Z")) { // boolean
            return new boolean[size];
        }
        if (obj.getClass().getName().contains("D")) {
            return new double[size];
        }
        return null; // UNKNOWN TYPE.
    }

    public static boolean isPrimative(Object obj) {
        if (obj == null)
            return false;
        if (obj.getClass().equals(Integer.class))
            return true;
        if (obj.getClass().equals(Float.class))
            return true;
        if (obj.getClass().equals(Character.class))
            return true;
        if (obj.getClass().equals(Long.class))
            return true;
        if (obj.getClass().equals(Short.class))
            return true;
        if (obj.getClass().equals(Byte.class))
            return true;
        if (obj.getClass().equals(Boolean.class))
            return true;
        if (obj.getClass().equals(Double.class))
            return true;
        return false;
    }

    public static Object getPrimativeForCL(Object obj) {
        if (obj == null)
            return null;
        if (obj.getClass().equals(Integer.class))
            return new int[]{(int) obj};
        if (obj.getClass().equals(Float.class))
            return new float[]{(float) obj};
        if (obj.getClass().equals(Character.class))
            return new char[]{(char) obj};
        if (obj.getClass().equals(Long.class))
            return new long[]{(long) obj};
        if (obj.getClass().equals(Short.class))
            return new short[]{(short) obj};
        if (obj.getClass().equals(Byte.class))
            return new byte[]{(byte) obj};
        if (obj.getClass().equals(Boolean.class))
            return new boolean[]{(boolean) obj};
        if (obj.getClass().equals(Double.class))
            return new double[]{(double) obj};
        return null;

    }
}
