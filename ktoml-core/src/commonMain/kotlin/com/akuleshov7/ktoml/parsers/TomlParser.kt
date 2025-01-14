package com.akuleshov7.ktoml.parsers

import com.akuleshov7.ktoml.TomlConfig
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.exceptions.ParseException
import com.akuleshov7.ktoml.tree.*
import com.akuleshov7.ktoml.tree.nodes.*
import kotlin.jvm.JvmInline

/**
 * @property config - object that stores configuration options for a parser
 */
@JvmInline
@Suppress("WRONG_MULTIPLE_MODIFIERS_ORDER")
public value class TomlParser(private val config: TomlInputConfig) {
    @Deprecated(
        message = "TomlConfig is deprecated; use TomlInputConfig instead. Will be removed in next releases."
    )
    public constructor(config: TomlConfig) : this(config.input)

    /**
     * Method for parsing of TOML string (this string should be split with newlines \n or \r\n)
     *
     * @param toml a raw string in the toml format with '\n' separator
     * @return the root TomlFile node of the Tree that we have built after parsing
     */
    public fun parseString(toml: String): TomlFile {
        // It looks like we need this hack to process line separator properly, as we don't have System.lineSeparator()
        val tomlString = toml.replace("\r\n", "\n")
        return parseStringsToTomlTree(tomlString.split("\n"), config)
    }

    /**
     * Parsing the list of strings to the TOML intermediate representation (TOML- abstract syntax tree).
     *
     * @param tomlLines list with toml strings (line by line)
     * @param config
     * @return the root node of the resulted toml tree
     * @throws InternalAstException - if toml node does not inherit TomlNode class
     */
    @Suppress("TOO_LONG_FUNCTION")
    public fun parseStringsToTomlTree(tomlLines: List<String>, config: TomlInputConfig): TomlFile {
        var currentParentalNode: TomlNode = TomlFile()
        // link to the head of the tree
        val tomlFileHead = currentParentalNode as TomlFile
        // need to trim empty lines BEFORE the start of processing
        val mutableTomlLines = tomlLines.toMutableList().trimEmptyTrailingLines()
        // here we always store the bucket of the latest created array of tables
        var latestCreatedBucket: TomlArrayOfTablesElement? = null

        val comments: MutableList<String> = mutableListOf()
        // variable to build multiline value as a single-line
        // then we can handle it as usually
        var multilineValueBuilt = StringBuilder()
        mutableTomlLines.forEachIndexed { index, line ->
            val lineNo = index + 1
            // comments and empty lines can easily be ignored in the TomlTree, but we cannot filter them out in mutableTomlLines
            // because we need to calculate and save lineNo
            if (line.isComment()) {
                comments += line.trimComment(config.allowEscapedQuotesInLiteralStrings)
            } else if (!line.isEmptyLine()) {
                // Parse the inline comment if any
                val inlineComment = line.trimComment(config.allowEscapedQuotesInLiteralStrings)

                // append all multiline values to StringBuilder as one line
                if (multilineValueBuilt.isNotEmpty() || line.isStartOfMultilineValue()
                ) {
                    // validation only for following lines
                    if (multilineValueBuilt.isNotEmpty()) {
                        line.validateIsFollowingPartOfMultilineValue(index, mutableTomlLines)
                    }
                    multilineValueBuilt.append(line.takeBeforeComment(config.allowEscapedQuotesInLiteralStrings).trim())
                    comments += inlineComment

                    if (!line.isEndOfMultilineValue()) {
                        return@forEachIndexed
                    }
                }
                val tomlLine = if (multilineValueBuilt.isNotBlank()) {
                    val tempLine = multilineValueBuilt.toString()
                    multilineValueBuilt = StringBuilder()
                    tempLine
                } else {
                    line
                }

                if (tomlLine.isTableNode()) {
                    if (tomlLine.isArrayOfTables()) {
                        // TomlArrayOfTables contains all information about the ArrayOfTables ([[array of tables]])
                        val tableArray = TomlArrayOfTables(tomlLine, lineNo)
                        val arrayOfTables = tomlFileHead.insertTableToTree(tableArray, latestCreatedBucket)
                        // creating a new empty element that will be used as an element in array and the parent for next key-value records
                        val newArrayElement = TomlArrayOfTablesElement(lineNo, comments, inlineComment)
                        // adding this element as a child to the array of tables
                        arrayOfTables.appendChild(newArrayElement)
                        // covering the case when the processed table does not contain nor key-value pairs neither tables (after our insertion)
                        // adding fake nodes to a previous table (it has no children because we have found another table right after)
                        currentParentalNode.insertStub()
                        // and setting this element as a current parent, so new key-records will be added to this bucket
                        currentParentalNode = newArrayElement
                        // here we set the bucket that will be incredibly useful when we will be inserting the next array of tables
                        latestCreatedBucket = newArrayElement
                    } else {
                        val tableSection = TomlTablePrimitive(tomlLine, lineNo, comments, inlineComment)
                        // if the table is the last line in toml, then it has no children, and we need to
                        // add at least fake node as a child
                        if (index == mutableTomlLines.lastIndex) {
                            tableSection.appendChild(TomlStubEmptyNode(lineNo))
                        }
                        // covering the case when the processed table does not contain nor key-value pairs neither tables (after our insertion)
                        // adding fake nodes to a previous table (it has no children because we have found another table right after)
                        currentParentalNode.insertStub()
                        currentParentalNode = tomlFileHead.insertTableToTree(tableSection)
                    }
                } else {
                    val keyValue = tomlLine.parseTomlKeyValue(lineNo, comments, inlineComment, config)
                    // inserting the key-value record to the tree
                    when {
                        keyValue is TomlKeyValue && keyValue.key.isDotted ->
                            // in case parser has faced dot-separated complex key (a.b.c) it should create proper table [a.b],
                            // because table is the same as dotted key
                            tomlFileHead
                                .insertTableToTree(keyValue.createTomlTableFromDottedKey(currentParentalNode))
                                .appendChild(keyValue)

                        keyValue is TomlInlineTable ->
                            // in case of inline tables (a = { b = "c" }) we need to create a new parental table and
                            // recursively process all inner nested tables (including inline and dotted)
                            tomlFileHead.insertTableToTree(keyValue.returnTable(tomlFileHead, currentParentalNode))

                        // otherwise, it should simply append the keyValue to the parent
                        else -> currentParentalNode.appendChild(keyValue)
                    }
                }

                comments.clear()
            }
        }
        return tomlFileHead
    }

    /**
     * @return true if string is a first line of multiline value declaration
     */
    private fun String.isStartOfMultilineValue(): Boolean {
        val line = this.takeBeforeComment(config.allowEscapedQuotesInLiteralStrings)
        val firstEqualsSign = line.indexOfFirst { it == '=' }
        if (firstEqualsSign == -1) {
            return false
        }
        val value = line.substring(firstEqualsSign + 1).trim()

        return value.startsWith("[") &&
                value.endsWith("]").not()
    }

    /**
     * @return true if string is a last line of multiline value declaration
     */
    private fun String.isEndOfMultilineValue(): Boolean =
            this.takeBeforeComment(config.allowEscapedQuotesInLiteralStrings)
                .trim()
                .endsWith("]")

    private fun String.validateIsFollowingPartOfMultilineValue(index: Int, mutableTomlLines: List<String>) {
        if (!this.isEndOfMultilineValue() && index == mutableTomlLines.lastIndex ||
                this.isValueDeclaration() ||
                this.isTableNode()
        ) {
            throw ParseException("Expected ']' in the end of array", index + 1)
        }
    }

    private fun String.isValueDeclaration(): Boolean {
        val line = this.takeBeforeComment(config.allowEscapedQuotesInLiteralStrings).trim()
        val firstEqualsSign = line.indexOfFirst { it == '=' }
        if (firstEqualsSign == -1) {
            return false
        }

        // '=' might be into string value
        val isStringValueLine = line.startsWith("\"") || line.startsWith("\'")
        return !isStringValueLine
    }

    private fun TomlNode.insertStub() {
        if (this.hasNoChildren() && this !is TomlFile && this !is TomlArrayOfTablesElement) {
            this.appendChild(TomlStubEmptyNode(this.lineNo))
        }
    }

    private fun MutableList<String>.trimEmptyTrailingLines(): MutableList<String> {
        if (this.isEmpty()) {
            return this
        }
        // removing all empty lines at the end, to cover empty tables properly
        while (this.last().isEmptyLine()) {
            this.removeLast()
            if (this.isEmpty()) {
                return this
            }
        }
        return this
    }

    private fun String.isArrayOfTables(): Boolean = this.trim().startsWith("[[")

    private fun String.isTableNode(): Boolean {
        val trimmed = this.trim()
        return trimmed.startsWith("[")
    }

    private fun String.isComment() = this.trim().startsWith("#")

    private fun String.isEmptyLine() = this.trim().isEmpty()
}

/**
 * factory adaptor to split the logic of parsing simple values from the logic of parsing collections (like Arrays)
 *
 * @param lineNo
 * @param comments
 * @param inlineComment
 * @param config
 * @return parsed toml node
 */
public fun String.parseTomlKeyValue(
    lineNo: Int,
    comments: List<String>,
    inlineComment: String,
    config: TomlInputConfig
): TomlNode {
    val keyValuePair = this.splitKeyValue(lineNo, config)
    return when {
        keyValuePair.second.startsWith("[") -> TomlKeyValueArray(keyValuePair, lineNo, comments, inlineComment, config)
        keyValuePair.second.startsWith("{") -> TomlInlineTable(keyValuePair, lineNo, comments, inlineComment, config)
        else -> TomlKeyValuePrimitive(keyValuePair, lineNo, comments, inlineComment, config)
    }
}
