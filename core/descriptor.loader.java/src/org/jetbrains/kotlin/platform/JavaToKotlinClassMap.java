/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.platform;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.builtins.CompanionObjectMapping;
import org.jetbrains.kotlin.builtins.KotlinBuiltIns;
import org.jetbrains.kotlin.builtins.functions.FunctionClassDescriptor;
import org.jetbrains.kotlin.descriptors.ClassDescriptor;
import org.jetbrains.kotlin.name.*;
import org.jetbrains.kotlin.resolve.DescriptorUtils;
import org.jetbrains.kotlin.resolve.jvm.JvmPrimitiveType;
import org.jetbrains.kotlin.types.KotlinType;
import org.jetbrains.kotlin.types.TypeUtils;

import java.lang.annotation.Annotation;
import java.util.*;

import static org.jetbrains.kotlin.builtins.KotlinBuiltIns.FQ_NAMES;
import static org.jetbrains.kotlin.resolve.descriptorUtil.DescriptorUtilsKt.getBuiltIns;
import static org.jetbrains.kotlin.resolve.descriptorUtil.DescriptorUtilsKt.getFqNameUnsafe;

public class JavaToKotlinClassMap implements PlatformToKotlinClassMap {
    public static final JavaToKotlinClassMap INSTANCE = new JavaToKotlinClassMap();

    private final Map<FqNameUnsafe, FqName> javaToKotlin = new HashMap<FqNameUnsafe, FqName>();
    private final Map<FqNameUnsafe, ClassId> kotlinToJava = new HashMap<FqNameUnsafe, ClassId>();

    private final Map<FqNameUnsafe, FqName> mutableToReadOnly = new HashMap<FqNameUnsafe, FqName>();
    private final Map<FqNameUnsafe, FqName> readOnlyToMutable = new HashMap<FqNameUnsafe, FqName>();
    private final CompanionObjectMapping companionObjectMapping;

    private JavaToKotlinClassMap() {
        add(Object.class, FQ_NAMES.any);
        add(String.class, FQ_NAMES.string);
        add(CharSequence.class, FQ_NAMES.charSequence);
        add(Throwable.class, FQ_NAMES.throwable);
        add(Cloneable.class, FQ_NAMES.cloneable);
        add(Number.class, FQ_NAMES.number);
        add(Comparable.class, FQ_NAMES.comparable);
        add(Enum.class, FQ_NAMES._enum);
        add(Annotation.class, FQ_NAMES.annotation);

        add(Iterable.class, FQ_NAMES.iterable, FQ_NAMES.mutableIterable);
        add(Iterator.class, FQ_NAMES.iterator, FQ_NAMES.mutableIterator);
        add(Collection.class, FQ_NAMES.collection, FQ_NAMES.mutableCollection);
        add(List.class, FQ_NAMES.list, FQ_NAMES.mutableList);
        add(Set.class, FQ_NAMES.set, FQ_NAMES.mutableSet);
        add(Map.class, FQ_NAMES.map, FQ_NAMES.mutableMap);
        add(Map.Entry.class, FQ_NAMES.mapEntry, FQ_NAMES.mutableMapEntry);
        add(ListIterator.class, FQ_NAMES.listIterator, FQ_NAMES.mutableListIterator);

        for (JvmPrimitiveType jvmType : JvmPrimitiveType.values()) {
            add(ClassId.topLevel(jvmType.getWrapperFqName()), KotlinBuiltIns.getPrimitiveFqName(jvmType.getPrimitiveType()));
        }

        companionObjectMapping = new CompanionObjectMapping();
        for (FqName fqName : companionObjectMapping.allClassesWithIntrinsicCompanions()) {
            add(ClassId.topLevel(
                    new FqName("kotlin.jvm.internal." + fqName.shortName().asString() + "CompanionObject")),
                    fqName.child(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT));
        }

        // TODO: support also functions with >= 23 parameters
        for (int i = 0; i < 23; i++) {
            add(ClassId.topLevel(new FqName("kotlin.jvm.functions.Function" + i)), KotlinBuiltIns.getFunctionFqName(i));

            FunctionClassDescriptor.Kind kFunction = FunctionClassDescriptor.Kind.KFunction;
            String kFun = kFunction.getPackageFqName() + "." + kFunction.getClassNamePrefix();
            addKotlinToJava(new FqName(kFun + i), ClassId.topLevel(new FqName(kFun)));
        }

        addKotlinToJava(FQ_NAMES.nothing.toSafe(), classId(Void.class));
    }

    /**
     * E.g.
     * java.lang.String -> kotlin.String
     * java.lang.Integer -> kotlin.Int
     * kotlin.jvm.internal.IntCompanionObject -> kotlin.Int.Companion
     * java.util.List -> kotlin.List
     * java.util.Map.Entry -> kotlin.Map.Entry
     * java.lang.Void -> null
     * kotlin.jvm.functions.Function3 -> kotlin.Function3
     */
    @Nullable
    public ClassDescriptor mapJavaToKotlin(@NotNull FqName fqName, @NotNull KotlinBuiltIns builtIns) {
        FqName kotlinFqName = javaToKotlin.get(fqName.toUnsafe());
        return kotlinFqName != null ? builtIns.getBuiltInClassByFqName(kotlinFqName) : null;
    }

    /**
     * E.g.
     * kotlin.Throwable -> java.lang.Throwable
     * kotlin.Int -> java.lang.Integer
     * kotlin.Int.Companion -> kotlin.jvm.internal.IntCompanionObject
     * kotlin.Nothing -> java.lang.Void
     * kotlin.IntArray -> null
     * kotlin.Function3 -> kotlin.jvm.functions.Function3
     * kotlin.reflect.KFunction3 -> kotlin.reflect.KFunction
     */
    @Nullable
    public ClassId mapKotlinToJava(@NotNull FqNameUnsafe kotlinFqName) {
        return kotlinToJava.get(kotlinFqName);
    }

    public boolean isMappedCompanion(@NotNull ClassDescriptor descriptor) {
        return companionObjectMapping.hasMappingToObject(descriptor);
    }

    private void add(
            @NotNull Class<?> javaClass,
            @NotNull FqName kotlinReadOnlyFqName,
            @NotNull FqName kotlinMutableFqName
    ) {
        ClassId javaClassId = classId(javaClass);

        add(javaClassId, kotlinReadOnlyFqName);
        addKotlinToJava(kotlinMutableFqName, javaClassId);

        mutableToReadOnly.put(kotlinMutableFqName.toUnsafe(), kotlinReadOnlyFqName);
        readOnlyToMutable.put(kotlinReadOnlyFqName.toUnsafe(), kotlinMutableFqName);
    }

    private void add(@NotNull ClassId javaClassId, @NotNull FqName kotlinFqName) {
        addJavaToKotlin(javaClassId, kotlinFqName);
        addKotlinToJava(kotlinFqName, javaClassId);
    }

    private void add(@NotNull Class<?> javaClass, @NotNull FqNameUnsafe kotlinFqName) {
        add(javaClass, kotlinFqName.toSafe());
    }

    private void add(@NotNull Class<?> javaClass, @NotNull FqName kotlinFqName) {
        add(classId(javaClass), kotlinFqName);
    }

    private void addJavaToKotlin(@NotNull ClassId javaClassId, @NotNull FqName kotlinFqName) {
        javaToKotlin.put(javaClassId.asSingleFqName().toUnsafe(), kotlinFqName);
    }

    private void addKotlinToJava(@NotNull FqName kotlinFqNameUnsafe, @NotNull ClassId javaClassId) {
        kotlinToJava.put(kotlinFqNameUnsafe.toUnsafe(), javaClassId);
    }

    @NotNull
    private static ClassId classId(@NotNull Class<?> clazz) {
        assert !clazz.isPrimitive() && !clazz.isArray() : "Invalid class: " + clazz;
        Class<?> outer = clazz.getDeclaringClass();
        return outer == null
               ? ClassId.topLevel(new FqName(clazz.getCanonicalName()))
               : classId(outer).createNestedClassId(Name.identifier(clazz.getSimpleName()));
    }

    @NotNull
    public Collection<ClassDescriptor> mapPlatformClass(@NotNull FqName fqName, @NotNull KotlinBuiltIns builtIns) {
        ClassDescriptor kotlinAnalog = mapJavaToKotlin(fqName, builtIns);
        if (kotlinAnalog == null) return Collections.emptySet();

        FqName kotlinMutableAnalogFqName = readOnlyToMutable.get(getFqNameUnsafe(kotlinAnalog));
        if (kotlinMutableAnalogFqName == null) return Collections.singleton(kotlinAnalog);

        return Arrays.asList(kotlinAnalog, builtIns.getBuiltInClassByFqName(kotlinMutableAnalogFqName));
    }

    @Override
    @NotNull
    public Collection<ClassDescriptor> mapPlatformClass(@NotNull ClassDescriptor classDescriptor) {
        FqNameUnsafe className = DescriptorUtils.getFqName(classDescriptor);
        return className.isSafe()
               ? mapPlatformClass(className.toSafe(), getBuiltIns(classDescriptor))
               : Collections.<ClassDescriptor>emptySet();
    }

    public boolean isMutable(@NotNull ClassDescriptor mutable) {
        return mutableToReadOnly.containsKey(DescriptorUtils.getFqName(mutable));
    }

    public boolean isMutable(@NotNull KotlinType type) {
        ClassDescriptor classDescriptor = TypeUtils.getClassDescriptor(type);
        return classDescriptor != null && isMutable(classDescriptor);
    }

    public boolean isReadOnly(@NotNull ClassDescriptor readOnly) {
        return readOnlyToMutable.containsKey(DescriptorUtils.getFqName(readOnly));
    }

    public boolean isReadOnly(@NotNull KotlinType type) {
        ClassDescriptor classDescriptor = TypeUtils.getClassDescriptor(type);
        return classDescriptor != null && isReadOnly(classDescriptor);
    }

    @NotNull
    public ClassDescriptor convertMutableToReadOnly(@NotNull ClassDescriptor mutable) {
        return convertToOppositeMutability(mutable, mutableToReadOnly, "mutable");
    }

    @NotNull
    private static ClassDescriptor convertToOppositeMutability(
            @NotNull ClassDescriptor descriptor,
            @NotNull Map<FqNameUnsafe, FqName> map,
            @NotNull String mutabilityKindName
    ) {
        FqName oppositeClassFqName = map.get(DescriptorUtils.getFqName(descriptor));
        if (oppositeClassFqName == null) {
            throw new IllegalArgumentException("Given class " + descriptor + " is not a " + mutabilityKindName + " collection");
        }
        return getBuiltIns(descriptor).getBuiltInClassByFqName(oppositeClassFqName);
    }

    @NotNull
    public ClassDescriptor convertReadOnlyToMutable(@NotNull ClassDescriptor readOnly) {
        return convertToOppositeMutability(readOnly, readOnlyToMutable, "read-only");
    }
}
