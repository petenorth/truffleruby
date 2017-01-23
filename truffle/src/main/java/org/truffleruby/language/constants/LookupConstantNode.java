/*
 * Copyright (c) 2015, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.language.constants;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.ConditionProfile;

import java.util.ArrayList;

import org.truffleruby.Layouts;
import org.truffleruby.core.module.ConstantLookupResult;
import org.truffleruby.core.module.ModuleOperations;
import org.truffleruby.language.LexicalScope;
import org.truffleruby.language.RubyConstant;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.WarnNode;
import org.truffleruby.language.control.RaiseException;

/**
 * Caches {@link ModuleOperations#lookupConstant}
 * and checks visibility.
 */
@NodeChildren({
        @NodeChild(value = "module", type = RubyNode.class),
        @NodeChild(value = "name", type = RubyNode.class)
})
public abstract class LookupConstantNode extends RubyNode implements LookupConstantInterface {

    private final boolean ignoreVisibility;
    private final boolean lookInObject;
    @Child private WarnNode warnNode;

    public static LookupConstantNode create(boolean ignoreVisibility, boolean lookInObject) {
        return LookupConstantNodeGen.create(ignoreVisibility, lookInObject, null, null);
    }

    public LookupConstantNode(boolean ignoreVisibility, boolean lookInObject) {
        this.ignoreVisibility = ignoreVisibility;
        this.lookInObject = lookInObject;
    }

    public abstract RubyConstant executeLookupConstant(
            VirtualFrame frame,
            Object module,
            String name);

    @Override
    public RubyConstant lookupConstant(VirtualFrame frame, Object module, String name) {
        return executeLookupConstant(frame, module, name);
    }

    @Specialization(
            guards = {
                    "module == cachedModule",
                    "isRubyModule(cachedModule)",
                    "guardName(name, cachedName, sameNameProfile)" },
            assumptions = "constant.getAssumptions()",
            limit = "getCacheLimit()")
    protected RubyConstant lookupConstant(
            VirtualFrame frame, DynamicObject module, String name,
            @Cached("module") DynamicObject cachedModule,
            @Cached("name") String cachedName,
            @Cached("doLookup(cachedModule, cachedName)") ConstantLookupResult constant,
            @Cached("isVisible(cachedModule, constant)") boolean isVisible,
            @Cached("createBinaryProfile()") ConditionProfile sameNameProfile) {
        if (!isVisible) {
            throw new RaiseException(coreExceptions().nameErrorPrivateConstant(module, name, this));
        }
        if (constant.isDeprecated()) {
            warnDeprecatedConstant(frame, name);
        }
        return constant.getConstant();
    }

    private void warnDeprecatedConstant(VirtualFrame frame, String name) {
        if (warnNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            warnNode = insert(new WarnNode());
        }
        warnNode.execute(frame, "constant ", name, " is deprecated");
    }

    @Specialization(guards = "isRubyModule(module)")
    protected RubyConstant lookupConstantUncached(VirtualFrame frame, DynamicObject module, String name,
            @Cached("createBinaryProfile()") ConditionProfile isVisibleProfile,
            @Cached("createBinaryProfile()") ConditionProfile isDeprecatedProfile) {
        ConstantLookupResult constant = doLookup(module, name);
        boolean isVisible = isVisible(module, constant);

        if (isVisibleProfile.profile(!isVisible)) {
            throw new RaiseException(coreExceptions().nameErrorPrivateConstant(module, name, this));
        }
        if (isDeprecatedProfile.profile(constant.isDeprecated())) {
            warnDeprecatedConstant(frame, name);
        }
        return constant.getConstant();
    }

    @Specialization(guards = "!isRubyModule(module)")
    protected RubyConstant lookupNotModule(Object module, String name) {
        throw new RaiseException(coreExceptions().typeErrorIsNotAClassModule(module, this));
    }

    protected boolean guardName(String name, String cachedName, ConditionProfile sameNameProfile) {
        // This is likely as for literal constant lookup the name does not change and Symbols
        // always return the same String.
        if (sameNameProfile.profile(name == cachedName)) {
            return true;
        } else {
            return name.equals(cachedName);
        }
    }

    @TruffleBoundary
    protected ConstantLookupResult doLookup(DynamicObject module, String name) {
        if (lookInObject) {
            return ModuleOperations.lookupConstantAndObject(getContext(), module, name, new ArrayList<>());
        } else {
            return ModuleOperations.lookupConstant(getContext(), module, name);
        }
    }

    @TruffleBoundary
    protected boolean isVisible(DynamicObject module, ConstantLookupResult constant) {
        return ignoreVisibility || constant.isVisibleTo(getContext(), LexicalScope.NONE, module);
    }

    protected int getCacheLimit() {
        return getContext().getOptions().CONSTANT_CACHE;
    }

}
