/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.lir;

import static com.oracle.graal.api.code.ValueUtil.*;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;

/**
 * This class is used to build the stack frame layout for a compiled method. A {@link StackSlot} is
 * used to index slots of the frame relative to the stack pointer. The frame size is only fixed
 * after register allocation when all spill slots have been allocated. Both the outgoing argument
 * area and the spill are can grow until then. Therefore, outgoing arguments are indexed from the
 * stack pointer, while spill slots are indexed from the beginning of the frame (and the total frame
 * size has to be added to get the actual offset from the stack pointer).
 */
public abstract class FrameMap {

    public final CodeCacheProvider runtime;
    public final TargetDescription target;
    public final RegisterConfig registerConfig;

    /**
     * The final frame size, not including the size of the
     * {@link Architecture#getReturnAddressSize() return address slot}. The value is only set after
     * register allocation is complete, i.e., after all spill slots have been allocated.
     */
    private int frameSize;

    /**
     * Initial size of the area occupied by spill slots and other stack-allocated memory blocks.
     */
    protected int initialSpillSize;

    /**
     * Size of the area occupied by spill slots and other stack-allocated memory blocks.
     */
    protected int spillSize;

    /**
     * Size of the area occupied by outgoing overflow arguments. This value is adjusted as calling
     * conventions for outgoing calls are retrieved. On some platforms, there is a minimum outgoing
     * size even if no overflow arguments are on the stack.
     */
    protected int outgoingSize;

    /**
     * Determines if this frame has values on the stack for outgoing calls.
     */
    private boolean hasOutgoingStackArguments;

    /**
     * The list of stack areas allocated in this frame that are present in every reference map.
     */
    private final List<StackSlot> objectStackBlocks;

    /**
     * Records whether an offset to an incoming stack argument was ever returned by
     * {@link #offsetForStackSlot(StackSlot)}.
     */
    private boolean accessesCallerFrame;

    /**
     * Creates a new frame map for the specified method.
     */
    public FrameMap(CodeCacheProvider runtime, TargetDescription target, RegisterConfig registerConfig) {
        this.runtime = runtime;
        this.target = target;
        this.registerConfig = registerConfig;
        this.frameSize = -1;
        this.outgoingSize = runtime.getMinimumOutgoingSize();
        this.objectStackBlocks = new ArrayList<>();
    }

    protected int returnAddressSize() {
        return target.arch.getReturnAddressSize();
    }

    protected int calleeSaveAreaSize() {
        CalleeSaveLayout csl = registerConfig.getCalleeSaveLayout();
        return csl != null ? csl.size : 0;
    }

    /**
     * Determines if an offset to an incoming stack argument was ever returned by
     * {@link #offsetForStackSlot(StackSlot)}.
     */
    public boolean accessesCallerFrame() {
        return accessesCallerFrame;
    }

    /**
     * Gets the frame size of the compiled frame, not including the size of the
     * {@link Architecture#getReturnAddressSize() return address slot}.
     * 
     * @return The size of the frame (in bytes).
     */
    public int frameSize() {
        assert frameSize != -1 : "frame size not computed yet";
        return frameSize;
    }

    /**
     * Determines if any space is used in the frame apart from the
     * {@link Architecture#getReturnAddressSize() return address slot}.
     */
    public boolean frameNeedsAllocating() {
        int unalignedFrameSize = spillSize - returnAddressSize();
        return hasOutgoingStackArguments || unalignedFrameSize != 0;
    }

    /**
     * Gets the total frame size of the compiled frame, including the size of the
     * {@link Architecture#getReturnAddressSize() return address slot}.
     * 
     * @return The total size of the frame (in bytes).
     */
    public abstract int totalFrameSize();

    /**
     * Gets the current size of this frame. This is the size that would be returned by
     * {@link #frameSize()} if {@link #finish()} were called now.
     */
    public abstract int currentFrameSize();

    /**
     * Aligns the given frame size to the stack alignment size and return the aligned size.
     * 
     * @param size the initial frame size to be aligned
     * @return the aligned frame size
     */
    protected abstract int alignFrameSize(int size);

    /**
     * Computes the final size of this frame. After this method has been called, methods that change
     * the frame size cannot be called anymore, e.g., no more spill slots or outgoing arguments can
     * be requested.
     */
    public void finish() {
        assert this.frameSize == -1 : "must only be set once";
        if (freedSlots != null) {
            // If the freed slots cover the complete spill area (except for the return
            // address slot), then the spill size is reset to its initial value.
            // Without this, frameNeedsAllocating() would never return true.
            int total = 0;
            for (StackSlot s : freedSlots) {
                total += target.arch.getSizeInBytes(s.getKind());
            }
            if (total == spillSize - initialSpillSize) {
                // reset spill area size
                spillSize = initialSpillSize;
            }
            freedSlots = null;
        }
        frameSize = currentFrameSize();
    }

    /**
     * Computes the offset of a stack slot relative to the frame register.
     * 
     * @param slot a stack slot
     * @return the offset of the stack slot
     */
    public int offsetForStackSlot(StackSlot slot) {
        assert (!slot.getRawAddFrameSize() && slot.getRawOffset() < outgoingSize) || (slot.getRawAddFrameSize() && slot.getRawOffset() < 0 && -slot.getRawOffset() <= spillSize) ||
                        (slot.getRawAddFrameSize() && slot.getRawOffset() >= 0);
        if (slot.isInCallerFrame()) {
            accessesCallerFrame = true;
        }
        return slot.getOffset(totalFrameSize());
    }

    /**
     * Computes the index of a stack slot relative to slot 0. This is also the bit index of stack
     * slots in the reference map.
     * 
     * @param slot a stack slot
     * @return the index of the stack slot
     */
    public int indexForStackSlot(StackSlot slot) {
        assert offsetForStackSlot(slot) % target.wordSize == 0;
        return offsetForStackSlot(slot) / target.wordSize;
    }

    /**
     * Gets the offset from the stack pointer to the stack area where callee-saved registers are
     * stored.
     * 
     * @return The offset to the callee save area (in bytes).
     */
    public abstract int offsetToCalleeSaveArea();

    /**
     * Informs the frame map that the compiled code calls a particular method, which may need stack
     * space for outgoing arguments.
     * 
     * @param cc The calling convention for the called method.
     */
    public void callsMethod(CallingConvention cc) {
        reserveOutgoing(cc.getStackSize());
    }

    /**
     * Reserves space for stack-based outgoing arguments.
     * 
     * @param argsSize The amount of space (in bytes) to reserve for stack-based outgoing arguments.
     */
    public void reserveOutgoing(int argsSize) {
        assert frameSize == -1 : "frame size must not yet be fixed";
        outgoingSize = Math.max(outgoingSize, argsSize);
        hasOutgoingStackArguments = hasOutgoingStackArguments || argsSize > 0;
    }

    /**
     * Reserves a new spill slot in the frame of the method being compiled. The returned slot is
     * aligned on its natural alignment, i.e., an 8-byte spill slot is aligned at an 8-byte
     * boundary.
     * 
     * @param kind The kind of the spill slot to be reserved.
     * @param additionalOffset
     * @return A spill slot denoting the reserved memory area.
     */
    protected abstract StackSlot allocateNewSpillSlot(PlatformKind kind, int additionalOffset);

    /**
     * Reserves a spill slot in the frame of the method being compiled. The returned slot is aligned
     * on its natural alignment, i.e., an 8-byte spill slot is aligned at an 8-byte boundary.
     * 
     * @param kind The kind of the spill slot to be reserved.
     * @return A spill slot denoting the reserved memory area.
     */
    public StackSlot allocateSpillSlot(PlatformKind kind) {
        assert frameSize == -1 : "frame size must not yet be fixed";
        if (freedSlots != null) {
            for (Iterator<StackSlot> iter = freedSlots.iterator(); iter.hasNext();) {
                StackSlot s = iter.next();
                if (s.getPlatformKind() == kind) {
                    iter.remove();
                    if (freedSlots.isEmpty()) {
                        freedSlots = null;
                    }
                    return s;
                }
            }
        }
        int size = target.arch.getSizeInBytes(kind);
        spillSize = NumUtil.roundUp(spillSize + size, size);
        return allocateNewSpillSlot(kind, 0);
    }

    private Set<StackSlot> freedSlots;

    /**
     * Frees a spill slot that was obtained via {@link #allocateSpillSlot(PlatformKind)} such that
     * it can be reused for the next allocation request for the same kind of slot.
     */
    public void freeSpillSlot(StackSlot slot) {
        if (freedSlots == null) {
            freedSlots = new HashSet<>();
        }
        freedSlots.add(slot);
    }

    /**
     * Reserves a block of memory in the frame of the method being compiled. The returned block is
     * aligned on a word boundary. If the requested size is 0, the method returns {@code null}.
     * 
     * @param size The size to reserve (in bytes).
     * @param refs Specifies if the block is all references. If true, the block will be in all
     *            reference maps for this method. The caller is responsible to initialize the memory
     *            block before the first instruction that uses a reference map.
     * @return A stack slot describing the begin of the memory block.
     */
    public StackSlot allocateStackBlock(int size, boolean refs) {
        assert frameSize == -1 : "frame size must not yet be fixed";
        if (size == 0) {
            return null;
        }
        spillSize = NumUtil.roundUp(spillSize + size, target.wordSize);

        if (refs) {
            assert size % target.wordSize == 0;
            StackSlot result = allocateNewSpillSlot(Kind.Object, 0);
            objectStackBlocks.add(result);
            for (int i = target.wordSize; i < size; i += target.wordSize) {
                objectStackBlocks.add(allocateNewSpillSlot(Kind.Object, i));
            }
            return result;

        } else {
            return allocateNewSpillSlot(target.wordKind, 0);
        }
    }

    /**
     * Initializes a reference map that covers all registers of the target architecture.
     */
    public BitSet initRegisterRefMap() {
        return new BitSet(target.arch.getRegisterReferenceMapBitCount());
    }

    /**
     * Initializes a reference map. Initially, the size is large enough to cover all the slots in
     * the frame. If the method has incoming reference arguments on the stack, the reference map
     * might grow later when such a reference is set.
     */
    public BitSet initFrameRefMap() {
        BitSet frameRefMap = new BitSet(frameSize() / target.wordSize);
        for (StackSlot slot : objectStackBlocks) {
            setReference(slot, null, frameRefMap);
        }
        return frameRefMap;
    }

    /**
     * Marks the specified location as a reference in the reference map of the debug information.
     * The tracked location can be a {@link RegisterValue} or a {@link StackSlot}. Note that a
     * {@link Constant} is automatically tracked.
     * 
     * @param location The location to be added to the reference map.
     * @param registerRefMap A register reference map, as created by {@link #initRegisterRefMap()}.
     * @param frameRefMap A frame reference map, as created by {@link #initFrameRefMap()}.
     */
    public void setReference(Value location, BitSet registerRefMap, BitSet frameRefMap) {
        if (location.getKind() == Kind.Object) {
            if (isRegister(location)) {
                registerRefMap.set(asRegister(location).number);
            } else if (isStackSlot(location)) {
                int index = indexForStackSlot(asStackSlot(location));
                frameRefMap.set(index);
            } else {
                assert isConstant(location);
            }
        }
    }
}
