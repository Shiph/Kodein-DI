package org.kodein.di.jxinject.internal

import org.kodein.di.DKodein
import org.kodein.di.TT
import org.kodein.di.TypeToken
import org.kodein.di.jxinject.*
import java.lang.reflect.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

internal class JxInjectorContainer {
    private val _qualifiers = HashMap<Class<out Annotation>, (Annotation) -> Any>()

    private val _setters = ConcurrentHashMap<Class<*>, List<DKodein.(Any) -> Any>>()

    private val _constructors = ConcurrentHashMap<Class<*>, DKodein.() -> Any>()

    init {
        registerQualifier(Named::class.java) { it.value }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <T: Annotation> registerQualifier(cls: Class<T>, tagProvider: (T) -> Any) {
        _qualifiers[cls] = tagProvider as (Annotation) -> Any
    }

    private fun getTagFromQualifier(el: AnnotatedElement): Any? {
        _qualifiers.forEach {
            val qualifier = el.getAnnotation(it.key)
            if (qualifier != null)
                return it.value.invoke(qualifier)
        }
        return null
    }

    private interface Element : AnnotatedElement {
        val classType: Class<*>
        val genericType: Type
        override fun isAnnotationPresent(annotationClass: Class<out Annotation>) = getAnnotation(annotationClass) != null
        override fun getDeclaredAnnotations(): Array<out Annotation> = annotations
        @Suppress("UNCHECKED_CAST")
        override fun <T : Annotation?> getAnnotation(annotationClass: Class<T>) = annotations.firstOrNull { annotationClass.isAssignableFrom(it.javaClass) } as T?
        override fun toString(): String
    }

    private fun getter(element: Element): DKodein.() -> Any? {
        val tag = getTagFromQualifier(element)

        val shouldErase = element.isAnnotationPresent(ErasedBinding::class.java)

        fun Type.boundType() = when {
            shouldErase -> rawType()
            this is WildcardType -> upperBounds[0]
            else -> this
        }

        val isOptional = element.isAnnotationPresent(OrNull::class.java)

        fun getterFunction(getter: DKodein.() -> Any?) = getter

        return when {
            element.classType == Lazy::class.java -> { // Must be first
                val boundType = (element.genericType as ParameterizedType).actualTypeArguments[0].boundType()
                class LazyElement : Element by element {
                    override val classType: Class<*> get() = boundType.rawType()
                    override val genericType: Type get() = boundType
                    override fun toString() = element.toString()
                }
                val getter = getter(LazyElement())

                getterFunction { lazy { getter() } }
            }
            element.isAnnotationPresent(ProviderFun::class.java) -> {
                if (element.classType != Function0::class.java)
                    throw IllegalArgumentException("When visiting $element, @ProviderFun annotated members must be of type Function0 () -> T")
                @Suppress("UNCHECKED_CAST")
                val boundType = TT((element.genericType as ParameterizedType).actualTypeArguments[0].boundType()) as TypeToken<out Any>
                when (isOptional) {
                    true  -> getterFunction { ProviderOrNull(boundType, tag) }
                    false -> getterFunction { Provider(boundType, tag) }
                }
            }
            element.classType == Provider::class.java -> {
                @Suppress("UNCHECKED_CAST")
                val boundType = TT((element.genericType as ParameterizedType).actualTypeArguments[0].boundType()) as TypeToken<out Any>
                fun (() -> Any).toJavaxProvider() = javax.inject.Provider { invoke() }
                when (isOptional) {
                    true  -> getterFunction { ProviderOrNull(boundType, tag)?.toJavaxProvider() }
                    false -> getterFunction { Provider(boundType, tag).toJavaxProvider() }
                }
            }
            element.isAnnotationPresent(FactoryFun::class.java) -> {
                if (element.classType != Function1::class.java)
                    throw IllegalArgumentException("When visiting $element, @FactoryFun annotated members must be of type Function1 (A) -> T")
                val fieldType = element.genericType as ParameterizedType
                val argType = TT(fieldType.actualTypeArguments[0].lower())
                @Suppress("UNCHECKED_CAST")
                val boundType = TT(fieldType.actualTypeArguments[1].boundType()) as TypeToken<out Any>
                when (isOptional) {
                    true  -> getterFunction { FactoryOrNull(argType, boundType, tag) }
                    false -> getterFunction { Factory(argType, boundType, tag) }
                }
            }
            else -> {
                @Suppress("UNCHECKED_CAST")
                val boundType = if (shouldErase) TT(element.classType) else TT(element.genericType) as TypeToken<out Any>
                when (isOptional) {
                    true  -> getterFunction { InstanceOrNull(boundType, tag) }
                    false -> getterFunction { Instance(boundType, tag) }
                }
            }
        }
    }

    private fun <M: AccessibleObject> fillSetters(
            members: Array<M>,
            elements: M.() -> Array<Element>,
            call: M.(Any, Array<Any?>) -> Unit,
            setters: MutableList<DKodein.(Any) -> Any>
    ) {
        members
            .filter { it.isAnnotationPresent(Inject::class.java) }
            .map { member ->
                val getters = member.elements()
                    .map { getter(it) }
                    .toTypedArray()

                @Suppress("DEPRECATION")
                val isAccessible = member.isAccessible

                setters += { receiver ->
                    val arguments = Array<Any?>(getters.size) { null }
                    getters.forEachIndexed { i, getter -> arguments[i] = getter() }

                    if (!isAccessible) member.isAccessible = true
                    try {
                        member.call(receiver, arguments)
                    }
                    finally {
                        if (!isAccessible) member.isAccessible = false
                    }
                }
            }
    }

    private tailrec fun fillMembersSetters(cls: Class<*>, setters: MutableList<DKodein.(Any) -> Any>) {
        if (cls == Any::class.java)
            return

        _setters[cls]?.let {
            setters += it
            return
        }

        @Suppress("DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE")
        class FieldElement(private val _field: Field) : Element, AnnotatedElement by _field {
            override val classType: Class<*> get() = _field.type
            override val genericType: Type get() = _field.genericType
            override fun toString() = _field.toString()
        }

        fillSetters(
            members = cls.declaredFields,
            elements = { arrayOf(FieldElement(this)) },
            call = { receiver, values -> set(receiver, values[0]) },
            setters = setters
        )

        class ParameterElement(private val _method: Method, private val _index: Int) : Element {
            override val classType: Class<*> get() = _method.parameterTypes[_index]
            override val genericType: Type get() = _method.genericParameterTypes[_index]
            override fun getAnnotations() = _method.parameterAnnotations[_index]
            override fun toString() = "Parameter ${_index + 1} of $_method"
        }

        fillSetters(
            members = cls.declaredMethods,
            elements = { (0 until parameterTypes.size).map { ParameterElement(this, it) }.toTypedArray() },
            call = { receiver, values -> invoke(receiver, *values) },
            setters = setters
        )

        return fillMembersSetters(cls.superclass, setters)
    }

    private fun createSetters(cls: Class<*>): List<DKodein.(Any) -> Any> {
        val setters = ArrayList<DKodein.(Any) -> Any>()
        fillMembersSetters(cls, setters)
        return setters
    }

    private fun findSetters(cls: Class<*>): List<DKodein.(Any) -> Any> = _setters.getOrPut(cls) { createSetters(cls) }

    internal fun inject(kodein: DKodein, receiver: Any) {
        findSetters(receiver.javaClass).forEach { kodein.it(receiver) }
    }

    private fun createConstructor(cls: Class<*>): DKodein.() -> Any {
        val constructor = cls.declaredConstructors.firstOrNull { it.isAnnotationPresent(Inject::class.java) }
                          ?:  if (cls.declaredConstructors.size == 1) cls.declaredConstructors[0]
                          else throw IllegalArgumentException("Class ${cls.name} must either have only one constructor or an @Inject annotated constructor")

        class ConstructorElement(private val _index: Int) : Element {
            override val classType: Class<*> get() = constructor.parameterTypes[_index]
            override val genericType: Type get() = constructor.genericParameterTypes[_index]
            override fun getAnnotations() = constructor.parameterAnnotations[_index]
            override fun toString() = "Parameter ${_index + 1} of $constructor"
        }

        val getters = (0 until constructor.parameterTypes.size).map { getter(ConstructorElement(it)) }

        @Suppress("DEPRECATION")
        val isAccessible = constructor.isAccessible

        return {
            val arguments = Array<Any?>(getters.size) { null }
            getters.forEachIndexed { i, getter -> arguments[i] = getter() }

            if (!isAccessible) constructor.isAccessible = true
            try {
                constructor.newInstance(*arguments)
            }
            finally {
                if (!isAccessible) constructor.isAccessible = false
            }
        }
    }

    private fun findConstructor(cls: Class<*>) = _constructors.getOrPut(cls) { createConstructor(cls) }

    /** @suppress */
    @JvmOverloads
    internal fun <T: Any> newInstance(kodein: DKodein, cls: Class<T>, injectFields: Boolean = true): T {
        val constructor = findConstructor(cls)

        @Suppress("UNCHECKED_CAST")
        val instance = kodein.constructor() as T

        if (injectFields)
            inject(kodein, instance)

        return instance
    }
}
