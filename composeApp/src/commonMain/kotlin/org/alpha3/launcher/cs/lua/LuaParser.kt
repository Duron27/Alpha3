package org.alpha3.launcher.cs.lua

/**
 * A parser for the Lua OpenMW runs (LuaJIT: Lua 5.1 with goto and labels). It makes the small syntax tree the checker
 * needs and reports the first syntax error with its line and column, as `luac -p` would.
 */
class LuaSyntaxError(val line: Int, val col: Int, message: String) : Exception(message)

data class Pos(val line: Int, val col: Int)

sealed class LNode { abstract val pos: Pos }

sealed class LExpr : LNode() {
    data class Name(val name: String, override val pos: Pos) : LExpr()
    data class Index(val obj: LExpr, val key: LExpr, val dot: Boolean, override val pos: Pos) : LExpr()
    data class Call(val fn: LExpr, val args: List<LExpr>, override val pos: Pos) : LExpr()
    data class Method(val obj: LExpr, val name: String, val args: List<LExpr>, override val pos: Pos) : LExpr()
    data class Str(val value: String, override val pos: Pos) : LExpr()
    data class Num(val text: String, override val pos: Pos) : LExpr()
    data class Lit(val text: String, override val pos: Pos) : LExpr()  // nil, true, false, ...
    data class Func(val params: List<String>, val vararg: Boolean, val body: List<LStat>, override val pos: Pos, val endPos: Pos) : LExpr()
    data class Table(val fields: List<TableField>, override val pos: Pos) : LExpr()
    data class Bin(val op: String, val left: LExpr, val right: LExpr, override val pos: Pos) : LExpr()
    data class Un(val op: String, val value: LExpr, override val pos: Pos) : LExpr()
    data class Paren(val value: LExpr, override val pos: Pos) : LExpr()
}

/** A table constructor's entry: name = v, [k] = v, or a positional v. */
data class TableField(val name: String?, val key: LExpr?, val value: LExpr)

sealed class LStat : LNode() {
    data class Local(val names: List<String>, val values: List<LExpr>, override val pos: Pos) : LStat()
    data class Assign(val targets: List<LExpr>, val values: List<LExpr>, override val pos: Pos) : LStat()
    data class CallStat(val call: LExpr, override val pos: Pos) : LStat()
    data class Do(val body: List<LStat>, override val pos: Pos) : LStat()
    data class While(val cond: LExpr, val body: List<LStat>, override val pos: Pos) : LStat()
    data class Repeat(val body: List<LStat>, val cond: LExpr, override val pos: Pos) : LStat()
    data class If(val conds: List<LExpr>, val blocks: List<List<LStat>>, val otherwise: List<LStat>?, override val pos: Pos) : LStat()
    data class NumFor(val name: String, val from: LExpr, val to: LExpr, val step: LExpr?, val body: List<LStat>, override val pos: Pos) : LStat()
    data class GenFor(val names: List<String>, val values: List<LExpr>, val body: List<LStat>, override val pos: Pos) : LStat()
    data class Function(val target: LExpr, val method: Boolean, val fn: LExpr.Func, override val pos: Pos) : LStat()
    data class LocalFunction(val name: String, val fn: LExpr.Func, override val pos: Pos) : LStat()
    data class Return(val values: List<LExpr>, override val pos: Pos) : LStat()
    data class Break(override val pos: Pos) : LStat()
    data class Goto(val label: String, override val pos: Pos) : LStat()
    data class Label(val label: String, override val pos: Pos) : LStat()
}

private enum class T { Name, Str, Num, Op, Eof }

private class Tok(val type: T, val text: String, val pos: Pos, val value: String = text)

private val KEYWORDS = setOf(
    "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in",
    "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while",
)

private class Lexer(val src: String) {
    var i = 0
    var line = 1
    var col = 1

    fun fail(msg: String, p: Pos = Pos(line, col)): Nothing = throw LuaSyntaxError(p.line, p.col, msg)

    private fun peek(o: Int = 0): Char = if (i + o < src.length) src[i + o] else '\u0000'
    private fun adv(): Char {
        val c = src[i++]
        if (c == '\n') { line++; col = 1 } else col++
        return c
    }

    /** The level of a long bracket at the cursor ([[ is 0, [==[ is 2), or -1. */
    private fun longOpen(): Int {
        if (peek() != '[') return -1
        var j = 1
        while (peek(j) == '=') j++
        return if (peek(j) == '[') j - 1 else -1
    }

    private fun longString(level: Int, start: Pos): String {
        repeat(level + 2) { adv() }
        if (peek() == '\r') adv()
        if (peek() == '\n') adv()
        val sb = StringBuilder()
        while (true) {
            if (i >= src.length) fail("unfinished long string or comment", start)
            if (peek() == ']') {
                var j = 1
                while (peek(j) == '=') j++
                if (j - 1 == level && peek(j) == ']') {
                    repeat(level + 2) { adv() }
                    return sb.toString()
                }
            }
            sb.append(adv())
        }
    }

    fun next(): Tok {
        while (true) {
            val c = peek()
            when {
                i >= src.length -> return Tok(T.Eof, "<eof>", Pos(line, col))
                c == '\n' || c == ' ' || c == '\t' || c == '\r' || c == '\u000c' -> adv()
                c == '-' && peek(1) == '-' -> {
                    adv(); adv()
                    val lvl = longOpen()
                    if (lvl >= 0) longString(lvl, Pos(line, col)) else while (i < src.length && peek() != '\n') adv()
                }
                c == '#' && i == 0 && peek(1) == '!' -> while (i < src.length && peek() != '\n') adv()
                else -> break
            }
        }
        val start = Pos(line, col)
        val c = peek()
        if (c.isLetter() || c == '_') {
            val b = i
            while (peek().isLetterOrDigit() || peek() == '_') adv()
            val word = src.substring(b, i)
            return Tok(if (word in KEYWORDS) T.Op else T.Name, word, start)
        }
        if (c.isDigit() || (c == '.' && peek(1).isDigit())) {
            val b = i
            if (c == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
                adv(); adv()
                while (peek().isLetterOrDigit() || peek() == '.' || ((peek() == '+' || peek() == '-') && (src[i - 1] == 'p' || src[i - 1] == 'P'))) adv()
            } else {
                while (peek().isLetterOrDigit() || peek() == '.' || ((peek() == '+' || peek() == '-') && (src[i - 1] == 'e' || src[i - 1] == 'E'))) adv()
            }
            val text = src.substring(b, i)
            val body = text.removeSuffix("ULL").removeSuffix("LL").removeSuffix("ull").removeSuffix("ll").removeSuffix("i").removeSuffix("I")
            val ok = if (body.startsWith("0x") || body.startsWith("0X")) Regex("0[xX]([0-9a-fA-F]*\\.?[0-9a-fA-F]*)([pP][+-]?\\d+)?").matches(body) && body.length > 2
            else Regex("(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?").matches(body)
            if (!ok) fail("malformed number near '$text'", start)
            return Tok(T.Num, text, start)
        }
        if (c == '"' || c == '\'') {
            adv()
            val sb = StringBuilder()
            while (true) {
                if (i >= src.length || peek() == '\n') fail("unfinished string", start)
                val ch = adv()
                if (ch == c) break
                if (ch == '\\') {
                    if (i >= src.length) fail("unfinished string", start)
                    when (val e = adv()) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'a', 'b', 'f', 'v' -> sb.append(' ')
                        '\\', '"', '\'', '\n' -> sb.append(e)
                        'x' -> { repeat(2) { if (!peek().isLetterOrDigit()) fail("invalid escape sequence", start); adv() } }
                        'z' -> while (peek().isWhitespace()) adv()
                        'u' -> { if (peek() != '{') fail("invalid escape sequence", start); while (i < src.length && peek() != '}') adv(); adv() }
                        else -> if (e.isDigit()) { var n = 1; while (n < 3 && peek().isDigit()) { adv(); n++ } } else fail("invalid escape sequence '\\$e'", start)
                    }
                } else sb.append(ch)
            }
            return Tok(T.Str, src.substring(0, 0), start, sb.toString())
        }
        val lvl = longOpen()
        if (lvl >= 0) return Tok(T.Str, "", start, longString(lvl, start))
        for (op in listOf("...", "..", "==", "~=", "<=", ">=", "::", "<<", ">>", "//")) {
            if (src.startsWith(op, i)) { repeat(op.length) { adv() }; return Tok(T.Op, op, start) }
        }
        if (c in "+-*/%^#<>=(){}[];:,.&|~") { adv(); return Tok(T.Op, c.toString(), start) }
        fail("unexpected symbol near '$c'", start)
    }
}

class LuaParser private constructor(src: String) {
    private val lex = Lexer(src)
    private var tok: Tok = lex.next()
    private var ahead: Tok? = null

    private fun near(t: Tok) = if (t.type == T.Eof) "<eof>" else if (t.type == T.Str) "string" else "'${t.text}'"
    private fun fail(msg: String, t: Tok = tok): Nothing = throw LuaSyntaxError(t.pos.line, t.pos.col, "$msg near ${near(t)}")

    private fun advance(): Tok {
        val t = tok
        tok = ahead ?: lex.next()
        ahead = null
        return t
    }

    private fun peekNext(): Tok = ahead ?: lex.next().also { ahead = it }
    private fun isOp(s: String) = tok.type == T.Op && tok.text == s
    private fun accept(s: String): Boolean = if (isOp(s)) { advance(); true } else false
    private fun expect(s: String, what: String = "'$s' expected"): Tok = if (isOp(s)) advance() else fail(what)
    private fun expectMatch(s: String, open: String, openPos: Pos): Tok {
        if (isOp(s)) return advance()
        if (tok.pos.line == openPos.line) fail("'$s' expected")
        fail("'$s' expected (to close '$open' at line ${openPos.line})")
    }

    private fun name(): String = if (tok.type == T.Name) advance().text else fail("<name> expected")

    private fun blockEnds() = tok.type == T.Eof || (tok.type == T.Op && tok.text in setOf("end", "else", "elseif", "until"))

    private fun block(): List<LStat> {
        val out = ArrayList<LStat>()
        while (!blockEnds()) {
            if (isOp("return")) {
                val p = advance().pos
                val values = if (blockEnds() || isOp(";")) emptyList() else exprList()
                accept(";")
                out += LStat.Return(values, p)
                if (!blockEnds()) fail("'end' expected")
                break
            }
            statement()?.let { out += it }
        }
        return out
    }

    private fun statement(): LStat? {
        val t = tok
        val p = t.pos
        if (t.type == T.Op) when (t.text) {
            ";" -> { advance(); return null }
            "if" -> {
                advance()
                val conds = arrayListOf(expr())
                expect("then", "'then' expected")
                val blocks = arrayListOf(block())
                var otherwise: List<LStat>? = null
                while (true) {
                    if (accept("elseif")) { conds += expr(); expect("then", "'then' expected"); blocks += block() }
                    else if (accept("else")) { otherwise = block(); expectMatch("end", "if", p); break }
                    else { expectMatch("end", "if", p); break }
                }
                return LStat.If(conds, blocks, otherwise, p)
            }
            "while" -> {
                advance(); val c = expr(); expect("do", "'do' expected"); val b = block(); expectMatch("end", "while", p)
                return LStat.While(c, b, p)
            }
            "do" -> { advance(); val b = block(); expectMatch("end", "do", p); return LStat.Do(b, p) }
            "for" -> {
                advance()
                val n1 = name()
                if (accept("=")) {
                    val from = expr(); expect(",", "',' expected"); val to = expr()
                    val step = if (accept(",")) expr() else null
                    expect("do", "'do' expected"); val b = block(); expectMatch("end", "for", p)
                    return LStat.NumFor(n1, from, to, step, b, p)
                }
                val names = arrayListOf(n1)
                while (accept(",")) names += name()
                expect("in", "'=' or 'in' expected")
                val values = exprList()
                expect("do", "'do' expected"); val b = block(); expectMatch("end", "for", p)
                return LStat.GenFor(names, values, b, p)
            }
            "repeat" -> {
                advance(); val b = block(); expectMatch("until", "repeat", p); val c = expr()
                return LStat.Repeat(b, c, p)
            }
            "function" -> {
                advance()
                var target: LExpr = LExpr.Name(name(), p)
                var method = false
                while (isOp(".")) { val dp = advance().pos; target = LExpr.Index(target, LExpr.Str(name(), dp), true, dp) }
                if (isOp(":")) { val dp = advance().pos; target = LExpr.Index(target, LExpr.Str(name(), dp), true, dp); method = true }
                return LStat.Function(target, method, funcBody(p, method), p)
            }
            "local" -> {
                advance()
                if (accept("function")) {
                    val n = name()
                    return LStat.LocalFunction(n, funcBody(p, false), p)
                }
                val names = arrayListOf(name())
                attrib()
                while (accept(",")) { names += name(); attrib() }
                val values = if (accept("=")) exprList() else emptyList()
                return LStat.Local(names, values, p)
            }
            "return" -> fail("'end' expected")
            "break" -> { advance(); return LStat.Break(p) }
            "goto" -> { advance(); return LStat.Goto(name(), p) }
            "::" -> { advance(); val n = name(); expect("::", "'::' expected"); return LStat.Label(n, p) }
        }
        // an assignment or a call
        val first = suffixed()
        if (isOp("=") || isOp(",")) {
            val targets = arrayListOf(first)
            while (accept(",")) targets += suffixed()
            for (target in targets) if (target !is LExpr.Name && target !is LExpr.Index) fail("syntax error")
            expect("=", "'=' expected")
            return LStat.Assign(targets, exprList(), p)
        }
        if (first !is LExpr.Call && first !is LExpr.Method) fail("syntax error")
        return LStat.CallStat(first, p)
    }

    /** Lua 5.4's <const> and <close>, which LuaJIT does not have: reported as LuaJIT would. */
    private fun attrib() { if (isOp("<")) fail("'=' expected") }

    private fun funcBody(p: Pos, method: Boolean): LExpr.Func {
        val open = expect("(", "'(' expected").pos
        val params = if (method) arrayListOf("self") else ArrayList()
        var vararg = false
        if (!isOp(")")) {
            do {
                if (isOp("...")) { advance(); vararg = true; break }
                params += name()
            } while (accept(","))
        }
        expectMatch(")", "(", open)
        val body = block()
        val end = tok.pos
        expectMatch("end", "function", p)
        return LExpr.Func(params, vararg, body, p, end)
    }

    private fun exprList(): List<LExpr> {
        val out = arrayListOf(expr())
        while (accept(",")) out += expr()
        return out
    }

    private fun primary(): LExpr {
        val t = tok
        if (t.type == T.Name) { advance(); return LExpr.Name(t.text, t.pos) }
        if (isOp("(")) {
            advance()
            val e = expr()
            expectMatch(")", "(", t.pos)
            return LExpr.Paren(e, t.pos)
        }
        fail("unexpected symbol")
    }

    private fun suffixed(): LExpr {
        var e = primary()
        while (true) {
            val t = tok
            e = when {
                isOp(".") -> { advance(); LExpr.Index(e, LExpr.Str(name(), t.pos), true, t.pos) }
                isOp("[") -> { advance(); val k = expr(); expect("]", "']' expected"); LExpr.Index(e, k, false, t.pos) }
                isOp(":") -> { advance(); val n = name(); LExpr.Method(e, n, callArgs(), t.pos) }
                isOp("(") || isOp("{") || tok.type == T.Str -> LExpr.Call(e, callArgs(), t.pos)
                else -> return e
            }
        }
    }

    private fun callArgs(): List<LExpr> {
        val t = tok
        return when {
            tok.type == T.Str -> listOf(LExpr.Str(advance().value, t.pos))
            isOp("{") -> listOf(table())
            isOp("(") -> {
                advance()
                if (accept(")")) emptyList() else exprList().also { expectMatch(")", "(", t.pos) }
            }
            else -> fail("function arguments expected")
        }
    }

    private fun table(): LExpr.Table {
        val open = expect("{").pos
        val fields = ArrayList<TableField>()
        while (!isOp("}")) {
            if (isOp("[")) {
                advance(); val k = expr(); expect("]", "']' expected"); expect("=", "'=' expected")
                fields += TableField(null, k, expr())
            } else if (tok.type == T.Name && peekNext().let { it.type == T.Op && it.text == "=" }) {
                val n = advance().text
                advance()
                fields += TableField(n, null, expr())
            } else fields += TableField(null, null, expr())
            if (!accept(",") && !accept(";")) break
        }
        expectMatch("}", "{", open)
        return LExpr.Table(fields, open)
    }

    private fun simple(): LExpr {
        val t = tok
        return when {
            t.type == T.Num -> { advance(); LExpr.Num(t.text, t.pos) }
            t.type == T.Str -> { advance(); LExpr.Str(t.value, t.pos) }
            isOp("nil") || isOp("true") || isOp("false") || isOp("...") -> { advance(); LExpr.Lit(t.text, t.pos) }
            isOp("{") -> table()
            isOp("function") -> { advance(); funcBody(t.pos, false) }
            else -> suffixed()
        }
    }

    private val binary = mapOf(
        "or" to (1 to 1), "and" to (2 to 2),
        "<" to (3 to 3), ">" to (3 to 3), "<=" to (3 to 3), ">=" to (3 to 3), "~=" to (3 to 3), "==" to (3 to 3),
        ".." to (9 to 8), "+" to (10 to 10), "-" to (10 to 10),
        "*" to (11 to 11), "/" to (11 to 11), "%" to (11 to 11),
        "^" to (14 to 13),
    )

    private fun expr(limit: Int = 0): LExpr {
        val t = tok
        var left = if (isOp("not") || isOp("-") || isOp("#")) {
            advance()
            LExpr.Un(t.text, expr(12), t.pos)
        } else simple()
        while (tok.type == T.Op) {
            val op = tok.text
            val (l, r) = binary[op] ?: break
            if (l <= limit) break
            val p = advance().pos
            left = LExpr.Bin(op, left, expr(r), p)
        }
        return left
    }

    companion object {
        /** The chunk's statements, or a [LuaSyntaxError]. */
        fun parse(src: String): List<LStat> {
            val p = LuaParser(src)
            val out = p.block()
            if (p.tok.type != T.Eof) p.fail("'<eof>' expected")
            return out
        }
    }
}
