/*
 * Copyright (c) 2015, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.cast;

import org.truffleruby.Layouts;
import org.truffleruby.core.CoreLibrary;
import org.truffleruby.core.numeric.FloatNodes;
import org.truffleruby.core.numeric.FloatNodesFactory;
import org.truffleruby.language.RubyContextSourceNode;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.dispatch.CallDispatchHeadNode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;

@NodeChild(value = "child", type = RubyNode.class)
public abstract class ToIntNode extends RubyContextSourceNode {

    @Child private CallDispatchHeadNode toIntNode;
    @Child private FloatNodes.ToINode floatToIntNode;

    private final ConditionProfile wasInteger = ConditionProfile.createBinaryProfile();
    private final ConditionProfile wasLong = ConditionProfile.createBinaryProfile();
    private final ConditionProfile wasLongInRange = ConditionProfile.createBinaryProfile();

    private final BranchProfile errorProfile = BranchProfile.create();

    public static ToIntNode create() {
        return ToIntNodeGen.create(null);
    }

    public int doInt(Object object) {
        // TODO CS 14-Nov-15 this code is crazy - should have separate nodes for ToRubyInteger and ToJavaInt

        final Object integerObject = executeIntOrLong(object);

        if (wasInteger.profile(integerObject instanceof Integer)) {
            return (int) integerObject;
        }

        if (wasLong.profile(integerObject instanceof Long)) {
            final long longValue = (long) integerObject;

            if (wasLongInRange.profile(CoreLibrary.fitsIntoInteger(longValue))) {
                return (int) longValue;
            }
        }

        errorProfile.enter();
        if (RubyGuards.isRubyBignum(object)) {
            throw new RaiseException(
                    getContext(),
                    coreExceptions().rangeError("bignum too big to convert into `long'", this));
        } else {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new UnsupportedOperationException(object.getClass().toString());
        }
    }

    public abstract Object executeIntOrLong(Object object);

    @Specialization
    protected int coerceInt(int value) {
        return value;
    }

    @Specialization
    protected long coerceLong(long value) {
        return value;
    }

    @Specialization(guards = "isRubyBignum(value)")
    protected DynamicObject coerceRubyBignum(DynamicObject value) {
        throw new RaiseException(
                getContext(),
                coreExceptions().rangeError("bignum too big to convert into `long'", this));
    }

    @Specialization
    protected Object coerceDouble(double value) {
        if (floatToIntNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            floatToIntNode = insert(FloatNodesFactory.ToINodeFactory.create(null));
        }
        return floatToIntNode.executeToI(value);
    }

    @Specialization
    protected Object coerceBoolean(boolean value,
            @Cached BranchProfile errorProfile) {
        return coerceObject(value, errorProfile);
    }

    @Specialization(guards = "!isRubyBignum(object)")
    protected Object coerceBasicObject(DynamicObject object,
            @Cached BranchProfile errorProfile) {
        return coerceObject(object, errorProfile);
    }

    private Object coerceObject(Object object, BranchProfile errorProfile) {
        if (toIntNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            toIntNode = insert(CallDispatchHeadNode.createPrivate());
        }

        final Object coerced;
        try {
            coerced = toIntNode.call(object, "to_int");
        } catch (RaiseException e) {
            errorProfile.enter();
            if (Layouts.BASIC_OBJECT.getLogicalClass(e.getException()) == coreLibrary().noMethodErrorClass) {
                throw new RaiseException(
                        getContext(),
                        coreExceptions().typeErrorNoImplicitConversion(object, "Integer", this));
            } else {
                throw e;
            }
        }

        if (coreLibrary().getLogicalClass(coerced) == coreLibrary().integerClass) {
            return coerced;
        } else {
            errorProfile.enter();
            throw new RaiseException(
                    getContext(),
                    coreExceptions().typeErrorBadCoercion(object, "Integer", "to_int", coerced, this));
        }
    }

}
