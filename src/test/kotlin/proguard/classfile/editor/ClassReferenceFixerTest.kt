/*
 * ProGuardCORE -- library to process Java bytecode.
 *
 * Copyright (c) 2002-2021 Guardsquare NV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package proguard.classfile.editor

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import proguard.classfile.AccessConstants.PUBLIC
import proguard.classfile.ClassConstants.NAME_JAVA_LANG_OBJECT
import proguard.classfile.editor.ClassReferenceFixer.shortKotlinNestedClassName
import proguard.classfile.kotlin.KotlinAnnotatable
import proguard.classfile.kotlin.KotlinAnnotation
import proguard.classfile.kotlin.KotlinAnnotationArgument
import proguard.classfile.kotlin.KotlinTypeMetadata
import proguard.classfile.kotlin.visitor.AllKotlinAnnotationArgumentVisitor
import proguard.classfile.kotlin.visitor.AllKotlinAnnotationVisitor
import proguard.classfile.kotlin.visitor.KotlinAnnotationArgumentVisitor
import proguard.classfile.kotlin.visitor.KotlinAnnotationVisitor
import proguard.classfile.kotlin.visitor.ReferencedKotlinMetadataVisitor
import proguard.classfile.util.ClassRenamer
import proguard.classfile.visitor.AllMethodVisitor
import proguard.classfile.visitor.ClassNameFilter
import proguard.classfile.visitor.MultiClassVisitor
import testutils.ClassPoolBuilder
import testutils.KotlinSource
import java.lang.RuntimeException

class ClassReferenceFixerTest : FreeSpec({
    "Kotlin nested class short names should be generated correctly" - {
        "with a valid Java name" {
            val referencedClass = ClassBuilder(55, PUBLIC, "OuterClass\$innerClass", NAME_JAVA_LANG_OBJECT).programClass
            shortKotlinNestedClassName("OuterClass", "innerClass", referencedClass) shouldBe "innerClass"
        }

        // dollar symbols are valid in Kotlin when surrounded by backticks `$innerClass`
        "with 1 dollar symbol" {
            val referencedClass = ClassBuilder(55, PUBLIC, "OuterClass\$\$innerClass", NAME_JAVA_LANG_OBJECT).programClass
            shortKotlinNestedClassName("OuterClass", "\$innerClass", referencedClass) shouldBe "\$innerClass"
        }

        "with multiple dollar symbols" {
            val referencedClass = ClassBuilder(55, PUBLIC, "OuterClass\$\$\$inner\$Class", NAME_JAVA_LANG_OBJECT).programClass
            shortKotlinNestedClassName("OuterClass", "\$\$inner\$Class", referencedClass) shouldBe "\$\$inner\$Class"
        }

        "when they have a new name" {
            val referencedClass = ClassBuilder(55, PUBLIC, "newOuterClass\$newInnerClass", NAME_JAVA_LANG_OBJECT).programClass
            shortKotlinNestedClassName("OuterClass", "innerClass", referencedClass) shouldBe "newInnerClass"
        }

        "when they have a new name with a package" {
            val referencedClass = ClassBuilder(55, PUBLIC, "mypackage/newOuterClass\$newInnerClass", NAME_JAVA_LANG_OBJECT).programClass
            shortKotlinNestedClassName("OuterClass", "innerClass", referencedClass) shouldBe "newInnerClass"
        }
    }

    "Kotlin annotations should be fixed correctly" - {
        val (programClassPool, _) = ClassPoolBuilder.fromSource(
            KotlinSource(
                "Test.kt",
                """
                import kotlin.reflect.KClass

                @Target(AnnotationTarget.TYPE)
                annotation class MyTypeAnnotation(
                    val string: String,
                    val byte: Byte,
                    val char: Char,
                    val short: Short,
                    val int: Int,
                    val long: Long,
                    val float: Float,
                    val double: Double,
                    val boolean: Boolean,
                    val uByte: UByte,
                    val uShort: UShort,
                    val uInt: UInt,
                    val uLong: ULong,
                    val kClass: KClass<*>,
                    val enum: MyEnum,
                    val array: Array<Foo>,
                    val annotation: Foo
                )

                val x: @MyTypeAnnotation(
                    string = "foo",
                    byte = 1,
                    char = 'a',
                    short = 1,
                    int = 1,
                    long = 1L,
                    float = 1f,
                    double = 1.0,
                    boolean = true,
                    uByte = 1u,
                    uShort = 1u,
                    uInt = 1u,
                    uLong = 1uL,
                    kClass = String::class,
                    enum = MyEnum.FOO,
                    array = arrayOf(Foo("foo"), Foo("bar")),
                    annotation = Foo("foo")) String = "foo"

                // extra helpers

                enum class MyEnum { FOO, BAR }
                annotation class Foo(val string: String)
                """.trimIndent()
            ),
            kotlincArguments = listOf("-Xuse-experimental=kotlin.ExperimentalUnsignedTypes")
        )

        with(programClassPool) {
            classesAccept(
                MultiClassVisitor(
                    ClassRenamer {
                        when (it.name) {
                            "MyTypeAnnotation" -> "MyRenamedTypeAnnotation"
                            "MyEnum" -> "MyRenamedEnum"
                            "Foo" -> "RenamedFoo"
                            else -> it.name
                        }
                    },
                    // Rename all the methods in the annotation class
                    ClassNameFilter(
                        "MyRenamedTypeAnnotation",
                        AllMethodVisitor(
                            ClassRenamer(
                                { it.name },
                                { clazz, member -> "renamed${member.getName(clazz).replaceFirstChar(Char::uppercase)}" }
                            )
                        )
                    )
                )
            )

            // The ClassReferenceFixer should rename everything correctly
            classesAccept(ClassReferenceFixer(false))
        }

        val annotationVisitor = spyk<KotlinAnnotationVisitor>()
        val fileFacadeClass = programClassPool.getClass("TestKt")

        programClassPool.classesAccept(ReferencedKotlinMetadataVisitor(AllKotlinAnnotationVisitor(annotationVisitor)))
        val annotation = slot<KotlinAnnotation>()

        "there should be 1 annotation visited" {
            verify(exactly = 1) { annotationVisitor.visitTypeAnnotation(fileFacadeClass, ofType(KotlinTypeMetadata::class), capture(annotation)) }
        }

        "the annotation class name should be correctly renamed" {
            annotation.captured.className shouldBe "MyRenamedTypeAnnotation"
            annotation.captured.referencedAnnotationClass shouldBe programClassPool.getClass("MyTypeAnnotation")
        }

        "the annotation argument value references should be correctly set" {

            val annotationArgVisitor = spyk<KotlinAnnotationArgumentVisitor>()

            programClassPool.classesAccept(
                ReferencedKotlinMetadataVisitor(
                    AllKotlinAnnotationVisitor(
                        AllKotlinAnnotationArgumentVisitor(
                            annotationArgVisitor
                        )
                    )
                )
            )

            verify(exactly = 17) {
                annotationArgVisitor.visitAnyArgument(
                    programClassPool.getClass("TestKt"),
                    ofType<KotlinAnnotatable>(),
                    ofType<KotlinAnnotation>(),
                    withArg { argument ->
                        with(programClassPool.getClass("MyTypeAnnotation")) {
                            when (argument.name) {
                                "renamedString" -> argument.referencedAnnotationMethod shouldBe findMethod("renamedString", "()Ljava/lang/String;")
                                "renamedByte" -> argument.referencedAnnotationMethod shouldBe findMethod("renamedByte", "()B")
                                "renamedChar" -> argument.referencedAnnotationMethod shouldBe findMethod("renamedChar", "()C")
                                "renamedShort" -> argument.referencedAnnotationMethod shouldBe findMethod("renamedShort", "()S")
                                "renamedInt" -> argument.referencedAnnotationMethod shouldBe findMethod("renamedInt", "()I")
                                "renamedLong" -> argument.referencedAnnotationMethod shouldBe findMethod("renamedLong", "()J")
                                "renamedFloat" -> argument.referencedAnnotationMethod shouldBe findMethod("renamedFloat", "()F")
                                "renamedDouble" -> argument.referencedAnnotationMethod shouldBe findMethod("renamedDouble", "()D")
                                "renamedBoolean" -> argument.referencedAnnotationMethod shouldBe findMethod("renamedBoolean", "()Z")
                                "renamedUByte" -> argument.referencedAnnotationMethod shouldBe findMethod("renamedUByte", "()B")
                                "renamedUShort" -> argument.referencedAnnotationMethod shouldBe findMethod("renamedUShort", "()S")
                                "renamedUInt" -> argument.referencedAnnotationMethod shouldBe findMethod("renamedUInt", "()I")
                                "renamedULong" -> argument.referencedAnnotationMethod shouldBe findMethod("renamedULong", "()J")
                                "renamedEnum" -> argument.referencedAnnotationMethod shouldBe findMethod("renamedEnum", "()LMyRenamedEnum;")
                                "renamedArray" -> argument.referencedAnnotationMethod shouldBe findMethod("renamedArray", "()[LRenamedFoo;")
                                "renamedAnnotation" -> argument.referencedAnnotationMethod shouldBe findMethod("renamedAnnotation", "()LRenamedFoo;")
                                "renamedKClass" -> argument.referencedAnnotationMethod shouldBe findMethod("renamedKClass", "()Ljava/lang/Class;")
                                else -> RuntimeException("Unexpected argument $argument")
                            }
                        }
                    },
                    ofType<KotlinAnnotationArgument.Value>()
                )
            }
        }
    }
})
