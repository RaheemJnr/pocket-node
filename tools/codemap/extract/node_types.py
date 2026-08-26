"""Grammar node-type names, observed from the pinned grammar versions.

Populated by probing tree-sitter-kotlin 1.1.0 and tree-sitter-rust 0.24.2
against real repo files. If a grammar is upgraded in requirements.txt,
re-probe and update this file -- it is the single point of coupling to
grammar internals. No other module should contain a literal node-type
string.

Observed quirks worth knowing:

* tree-sitter-kotlin 1.1.0 reports a parse error on single-line bodies
  such as `interface I { fun m() }`. The multi-line form parses cleanly.
  All 179 real Kotlin files in this repo parse without error; only
  hand-written one-liners trip it. Keep test fixtures multi-line.
* Kotlin uses `identifier`, not `simple_identifier`.
* `interface` and `enum class` both produce `class_declaration`;
  `object` produces `object_declaration`.
* Rust doc comments are a `doc_comment` child inside a `line_comment`.
* Rust `impl_item` exposes its type via the `type` field and, for trait
  impls, the trait via the `trait` field. It has no `name` field.
"""


class KT:
    SOURCE = "source_file"

    PACKAGE = "package_header"
    IMPORT = "import"
    QUALIFIED_IDENT = "qualified_identifier"

    CLASS = "class_declaration"          # also interface, enum class
    OBJECT = "object_declaration"
    FUNCTION = "function_declaration"
    PROPERTY = "property_declaration"
    VARIABLE = "variable_declaration"

    CLASS_BODY = "class_body"
    FUNCTION_BODY = "function_body"
    BLOCK = "block"

    PRIMARY_CONSTRUCTOR = "primary_constructor"
    CLASS_PARAM = "class_parameter"
    FUNCTION_PARAMS = "function_value_parameters"
    PARAM = "parameter"

    MODIFIERS = "modifiers"
    ANNOTATION = "annotation"
    DELEGATION = "delegation_specifier"

    CALL = "call_expression"
    NAVIGATION = "navigation_expression"
    VALUE_ARGS = "value_arguments"

    IDENT = "identifier"
    USER_TYPE = "user_type"

    LINE_COMMENT = "line_comment"
    BLOCK_COMMENT = "block_comment"

    #: Node types that introduce a new type scope.
    TYPE_DECLS = (CLASS, OBJECT)


class RS:
    SOURCE = "source_file"

    USE = "use_declaration"
    MOD = "mod_item"

    FUNCTION = "function_item"
    STRUCT = "struct_item"
    ENUM = "enum_item"
    TRAIT = "trait_item"
    IMPL = "impl_item"

    ATTRIBUTE = "attribute_item"
    ATTRIBUTE_INNER = "attribute"

    CALL = "call_expression"
    FIELD_EXPR = "field_expression"
    SCOPED_IDENT = "scoped_identifier"

    IDENT = "identifier"
    TYPE_IDENT = "type_identifier"

    LINE_COMMENT = "line_comment"
    DOC_COMMENT = "doc_comment"

    #: Node types that declare a named type.
    TYPE_DECLS = (STRUCT, ENUM, TRAIT)
