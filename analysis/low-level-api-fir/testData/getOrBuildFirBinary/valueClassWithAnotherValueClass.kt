// DECLARATION_TYPE: org.jetbrains.kotlin.psi.KtClass
// MAIN_FILE_NAME: ValueClass
package pack

@JvmInline
value class AnotherValueClass(val s: String)

@JvmInline
value class ValueClass(val value: AnotherValueClass)