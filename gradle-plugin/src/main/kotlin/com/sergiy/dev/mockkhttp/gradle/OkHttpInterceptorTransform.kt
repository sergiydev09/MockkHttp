package com.sergiy.dev.mockkhttp.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.AdviceAdapter

/**
 * ASM class visitor factory that injects MockkHttpInterceptor into OkHttpClient.Builder.
 *
 * This transforms:
 * ```
 * val client = OkHttpClient.Builder().build()
 * ```
 *
 * Into:
 * ```
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(MockkHttpInterceptor(null))
 *     .build()
 * ```
 */
abstract class OkHttpInterceptorTransform : AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        return OkHttpClassVisitor(nextClassVisitor, classContext.currentClassData.className)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        // Skip our own library classes
        return !classData.className.startsWith("com.sergiy.dev.mockkhttp.interceptor")
    }
}

/**
 * ClassVisitor that processes each class looking for OkHttpClient usage.
 */
class OkHttpClassVisitor(
    nextVisitor: ClassVisitor,
    private val className: String
) : ClassVisitor(Opcodes.ASM9, nextVisitor) {

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        return OkHttpMethodVisitor(mv, access, name, descriptor, className)
    }
}

/**
 * MethodVisitor that injects interceptor calls into OkHttpClient.Builder.build().
 */
class OkHttpMethodVisitor(
    methodVisitor: MethodVisitor,
    access: Int,
    name: String,
    descriptor: String,
    private val className: String
) : AdviceAdapter(Opcodes.ASM9, methodVisitor, access, name, descriptor) {

    companion object {
        private var hasLoggedInjection = false
    }

    private var detectedBuilder = false

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean
    ) {
        // Detect OkHttpClient.Builder() constructor
        if (opcode == Opcodes.INVOKESPECIAL &&
            name == "<init>" &&
            isOkHttpBuilder(owner)
        ) {
            detectedBuilder = true
        }

        // Inject interceptor BEFORE .build() is called
        if (detectedBuilder &&
            opcode == Opcodes.INVOKEVIRTUAL &&
            name == "build" &&
            isOkHttpBuilder(owner)
        ) {
            // Stack: [builder]
            injectInterceptor(owner)
            // Stack: [builder_with_interceptor]
        }

        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    private fun injectInterceptor(builderOwner: String) {
        // Log only once per build
        if (!hasLoggedInjection) {
            println("🔌 MockkHttp: Injecting interceptor into OkHttpClient")
            hasLoggedInjection = true
        }

        // Stack before: [builder]

        // Duplicate builder reference for addInterceptor call
        mv.visitInsn(Opcodes.DUP)
        // Stack: [builder, builder]

        // Create new MockkHttpInterceptor(null)
        mv.visitTypeInsn(
            Opcodes.NEW,
            "com/sergiy/dev/mockkhttp/interceptor/MockkHttpInterceptor"
        )
        // Stack: [builder, builder, interceptor_ref]

        mv.visitInsn(Opcodes.DUP)
        // Stack: [builder, builder, interceptor_ref, interceptor_ref]

        // Constructor takes Context (we pass null, will use reflection internally)
        mv.visitInsn(Opcodes.ACONST_NULL)
        // Stack: [builder, builder, interceptor_ref, interceptor_ref, null]

        // Call MockkHttpInterceptor(Context?)
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "com/sergiy/dev/mockkhttp/interceptor/MockkHttpInterceptor",
            "<init>",
            "(Landroid/content/Context;)V",
            false
        )
        // Stack: [builder, builder, interceptor]

        // Call builder.addInterceptor(interceptor)
        val addInterceptorDescriptor = if (builderOwner.contains("okhttp3")) {
            "(Lokhttp3/Interceptor;)Lokhttp3/OkHttpClient\$Builder;"
        } else {
            "(Lcom/squareup/okhttp/Interceptor;)Lcom/squareup/okhttp/OkHttpClient\$Builder;"
        }

        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            builderOwner,
            "addInterceptor",
            addInterceptorDescriptor,
            false
        )
        // Stack: [builder, builder_with_interceptor]

        // Pop the original builder, keep the one with interceptor
        mv.visitInsn(Opcodes.POP)
        // Stack: [builder_with_interceptor]
    }

    private fun isOkHttpBuilder(owner: String): Boolean {
        return owner == "okhttp3/OkHttpClient\$Builder" ||
                owner == "com/squareup/okhttp/OkHttpClient\$Builder"
    }
}
