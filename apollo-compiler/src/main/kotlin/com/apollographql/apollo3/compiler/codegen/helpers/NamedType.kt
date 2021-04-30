package com.apollographql.apollo3.compiler.codegen.helpers

import com.apollographql.apollo3.api.Optional
import com.apollographql.apollo3.compiler.applyIf
import com.apollographql.apollo3.compiler.codegen.CgContext
import com.apollographql.apollo3.compiler.codegen.Identifier
import com.apollographql.apollo3.compiler.unified.ir.IrInputField
import com.apollographql.apollo3.compiler.unified.ir.IrOptionalType
import com.apollographql.apollo3.compiler.unified.ir.IrType
import com.apollographql.apollo3.compiler.unified.ir.IrVariable
import com.apollographql.apollo3.compiler.unified.ir.isOptional
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.asClassName

class NamedType(
    val graphQlName: String,
    val description: String?,
    val deprecationReason: String?,
    val type: IrType,
)


internal fun NamedType.toParameterSpec(context: CgContext): ParameterSpec {
  return ParameterSpec
      .builder(
          // we use property for parameters as these are ultimately data classes
          name = context.layout.propertyName(graphQlName),
          type = context.resolver.resolveType(type)
      )
      .applyIf(description?.isNotBlank() == true) { addKdoc("%L\n", description!!) }
      .applyIf(type.isOptional()) { defaultValue("%T", Optional.Absent::class.asClassName()) }
      .build()
}


fun IrInputField.toNamedType() = NamedType(
    graphQlName = name,
    type = type,
    description = description,
    deprecationReason = deprecationReason,
)

fun IrVariable.toNamedType() = NamedType(
    graphQlName = name,
    type = type,
    description = null,
    deprecationReason = null,
)


internal fun List<NamedType>.writeToResponseCodeBlock(context: CgContext): CodeBlock {
  val builder = CodeBlock.builder()
  forEach {
    builder.add(it.writeToResponseCodeBlock(context))
  }
  return builder.build()
}

internal fun NamedType.writeToResponseCodeBlock(context: CgContext): CodeBlock {
  val adapterInitializer = context.resolver.adapterInitializer(type)
  val builder = CodeBlock.builder()
  val propertyName = context.layout.propertyName(graphQlName)

  if (type.isOptional()) {
    builder.beginControlFlow("if (${Identifier.value}.$propertyName is %T)", Optional.Present::class.asClassName())
  }
  builder.addStatement("${Identifier.writer}.name(%S)", graphQlName)
  builder.addStatement(
      "%L.${Identifier.toResponse}(${Identifier.writer}, ${Identifier.responseAdapterCache}, ${Identifier.value}.$propertyName)",
      adapterInitializer
  )
  if (type.isOptional()) {
    builder.endControlFlow()
  }

  return builder.build()
}