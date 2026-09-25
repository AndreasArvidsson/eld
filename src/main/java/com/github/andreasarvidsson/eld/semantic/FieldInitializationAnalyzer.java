package com.github.andreasarvidsson.eld.semantic;

import com.github.andreasarvidsson.eld.parser.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Tracks definite and possible field assignments on constructor control-flow paths. */
final class FieldInitializationAnalyzer {
    private enum Exit {
        NORMAL,
        RETURN,
        THROW,
        BREAK,
        CONTINUE,
        YIELD
    }

    private record Path(
        Set<Symbol> definite, Set<Symbol> possible, Exit exit,
        boolean baseDefinite, boolean basePossible
    ) {
        Path withExit(final Exit exit) {
            return new Path(
                definite,
                possible,
                exit,
                baseDefinite,
                basePossible
            );
        }
    }

    private int pendingFinalizers;
    private final SemanticModel model;
    private final ClassDeclaration declaration;
    private final Set<Symbol> fields = new HashSet<>();
    private final Set<Symbol> defaults = new HashSet<>();

    FieldInitializationAnalyzer(
        final SemanticModel model,
        final ClassDeclaration declaration
    ) {
        this.model = model;
        this.declaration = declaration;
        for (final MemberDeclaration memberDeclaration : declaration
            .members()) {
            final Declaration member = memberDeclaration.declaration();
            if (member instanceof VariableDeclaration field) {
                final Symbol symbol = model.getSymbol(field.name());
                fields.add(symbol);
                defaults.add(symbol);
            }
            else if (member instanceof UninitializedVariableDeclaration field) {
                fields.add(model.getSymbol(field.name()));
            }
        }
    }

    void analyze(final @Nullable ConstructorDeclaration constructor) {
        final boolean initialized =
            constructor == null || !constructor.hasExplicitSuperCall();
        final Path initial =
            new Path(defaults, defaults, Exit.NORMAL, initialized, initialized);
        if (constructor == null) {
            for (final MemberDeclaration memberDeclaration : declaration
                .members()) {
                final Declaration member = memberDeclaration.declaration();
                if (member instanceof UninitializedVariableDeclaration field) {
                    throw new SemanticException(
                        field.range(),
                        "Field '%s' is not definitely initialized",
                        field.name().name()
                    );
                }
            }
            return;
        }
        for (final Path path : walk(constructor.body(), List.of(initial))) {
            if (path.exit() == Exit.NORMAL || path.exit() == Exit.RETURN) {
                requireComplete(path, constructor);
            }
        }
    }

    private void requireComplete(final Path path, final AstNode node) {
        requireBaseInitialized(path, node);
        for (final Symbol field : fields) {
            if (!path.definite().contains(field)) {
                throw new SemanticException(
                    node.range(),
                    "Field '%s' is not definitely initialized",
                    field.name()
                );
            }
        }
    }

    private void requireBaseInitialized(final Path path, final AstNode node) {
        if (!path.baseDefinite()) {
            throw new SemanticException(
                node.range(),
                "Base constructor must execute before using 'this' or completing the constructor"
            );
        }
    }

    private static Expression unwrap(final Expression expression) {
        return expression instanceof GroupingExpression group
            ? unwrap(group.expression())
            : expression;
    }

    private @Nullable Symbol ownField(final Expression expression) {
        if (
            unwrap(expression) instanceof MemberExpression member
                && unwrap(member.target()) instanceof ThisExpression
        ) {
            final Symbol symbol = model.getReference(member.member());
            if (fields.contains(symbol)) {
                return symbol;
            }
        }
        return null;
    }

    private List<Path> walk(
        final @Nullable AstNode node,
        final List<Path> paths
    ) {
        if (node == null) {
            return paths;
        }
        final List<Path> result = new ArrayList<>();
        for (final Path path : paths) {
            if (path.exit() != Exit.NORMAL) {
                result.add(path);
            }
            else {
                result.addAll(step(node, path));
            }
        }
        return merge(result);
    }

    private List<Path> sequence(
        final List<? extends AstNode> nodes,
        List<Path> paths
    ) {
        for (final AstNode node : nodes) {
            paths = walk(node, paths);
        }
        return paths;
    }

    private List<Path> step(final AstNode node, final Path path) {
        final List<Path> paths = List.of(path);
        switch (node) {
            case BlockStatement block:
                return sequence(block.items(), paths);
            case DeclarationStatement statement:
                return walk(statement.declaration(), paths);
            case VariableDeclaration variable:
                return walk(variable.initializer(), paths);
            case DestructuringDeclaration destructuring:
                return walk(destructuring.initializer(), paths);
            case DestructuringAssignmentStatement destructuring:
                return walk(destructuring.value(), paths);
            case ExpressionStatement statement:
                return walk(statement.expression(), paths);
            case SuperConstructorCall call: {
                if (path.basePossible()) {
                    throw new SemanticException(
                        call.range(),
                        "Base constructor may already have executed"
                    );
                }
                final List<Path> result = new ArrayList<>();
                for (final Path argumentPath : sequence(
                    call.arguments(),
                    paths
                )) {
                    result.add(
                        argumentPath.exit() == Exit.NORMAL
                            ? new Path(
                                argumentPath.definite(),
                                argumentPath.possible(),
                                Exit.NORMAL,
                                true,
                                true
                            )
                            : argumentPath
                    );
                }
                return result;
            }
            case ThrowStatement statement:
                return changeExit(
                    walk(statement.value(), paths),
                    Exit.NORMAL,
                    Exit.THROW
                );
            case TryStatement statement:
                return tryStatement(statement, path);
            case ReturnStatement statement:
                if (pendingFinalizers == 0) {
                    requireComplete(path, statement);
                }
                return List.of(path.withExit(Exit.RETURN));
            case BreakStatement ignored:
                return List.of(path.withExit(Exit.BREAK));
            case ContinueStatement ignored:
                return List.of(path.withExit(Exit.CONTINUE));
            case YieldStatement statement:
                return changeExit(
                    walk(statement.value(), paths),
                    Exit.NORMAL,
                    Exit.YIELD
                );
            case GroupingExpression grouping:
                return walk(grouping.expression(), paths);
            case ThisExpression self:
                requireComplete(path, self);
                return paths;
            case LambdaExpression lambda:
                if (!path.baseDefinite()) {
                    model.setReceiverlessLambda(lambda);
                }
                // Capturing the receiver exposes it to code outside the constructor.
                // Do not execute the deferred body or count its field assignments.
                AstTraversal.walk(lambda.body(), child -> {
                    if (child instanceof ThisExpression) {
                        requireComplete(path, lambda);
                    }
                });
                return paths;
            case ObjectExpression object:
                return sequence(
                    object.members().stream().map(ObjectEntry::value).toList(),
                    paths
                );
            case MemberExpression member: {
                final Symbol field = ownField(member);
                if (field == null) {
                    if (
                        unwrap(member.target()) instanceof ThisExpression
                            && model.getReference(
                                member.member()
                            ) instanceof VariableSymbol
                    ) {
                        requireBaseInitialized(path, member);
                        return paths;
                    }
                    return walk(member.target(), paths);
                }
                requireBaseInitialized(path, member);
                if (!path.definite().contains(field)) {
                    throw new SemanticException(
                        member.range(),
                        "Field '%s' is read before initialization",
                        field.name()
                    );
                }
                return paths;
            }
            case AssignmentExpression assignment: {
                final Symbol field = ownField(assignment.target());
                if (field == null) {
                    return walk(
                        assignment.value(),
                        walk(assignment.target(), paths)
                    );
                }
                requireBaseInitialized(path, assignment);
                final List<Path> values = walk(assignment.value(), paths);
                final List<Path> assigned = new ArrayList<>();
                for (final Path value : values) {
                    if (value.exit() != Exit.NORMAL) {
                        assigned.add(value);
                        continue;
                    }
                    if (
                        field instanceof VariableSymbol variable
                            && variable.mutability() == Mutability.CONST
                            && value.possible().contains(field)
                    ) {
                        throw new SemanticException(
                            assignment.range(),
                            "Const field '%s' may already be initialized",
                            field.name()
                        );
                    }
                    final Set<Symbol> definite =
                        new HashSet<>(value.definite());
                    final Set<Symbol> possible =
                        new HashSet<>(value.possible());
                    definite.add(field);
                    possible.add(field);
                    assigned.add(
                        new Path(
                            definite,
                            possible,
                            Exit.NORMAL,
                            value.baseDefinite(),
                            value.basePossible()
                        )
                    );
                }
                return assigned;
            }
            case FormatStringExpression format: {
                List<Path> result = paths;
                for (final Expression part : format.parts()) {
                    result = walk(part, result);
                }
                return result;
            }
            case PostfixExpression postfix:
                return walk(postfix.operand(), paths);
            case UnaryExpression unary:
                return walk(unary.operand(), paths);
            case BinaryExpression binary: {
                final List<Path> left = walk(binary.left(), paths);
                final List<Path> right = walk(binary.right(), left);
                if (
                    binary.operator() == BinaryOperator.AND
                        || binary.operator() == BinaryOperator.OR
                ) {
                    final List<Path> branches = new ArrayList<>(left);
                    branches.addAll(right);
                    return merge(branches);
                }
                return right;
            }
            case TernaryExpression ternary: {
                final List<Path> condition = walk(ternary.condition(), paths);
                final List<Path> branches =
                    new ArrayList<>(walk(ternary.thenBranch(), condition));
                branches.addAll(walk(ternary.elseBranch(), condition));
                return merge(branches);
            }
            case IfExpression conditional: {
                List<Path> condition = walk(conditional.condition(), paths);
                final List<Path> branches =
                    new ArrayList<>(walk(conditional.thenBranch(), condition));
                for (final ElseIfBranch branch : conditional.elifBranches()) {
                    condition = walk(branch.condition(), condition);
                    branches.addAll(walk(branch.branch(), condition));
                }
                branches.addAll(walk(conditional.elseBranch(), condition));
                return model.getExpressionType(conditional) == BuiltinType.VOID
                    ? merge(branches)
                    : merge(changeExit(branches, Exit.YIELD, Exit.NORMAL));
            }
            case SwitchExpression selection: {
                List<Path> subject = walk(selection.subject(), paths);
                final List<Path> branches = new ArrayList<>();
                for (final SwitchBranch branch : selection.branches()) {
                    subject = sequence(branch.matches(), subject);
                    branches.addAll(walk(branch.body(), subject));
                }
                branches.addAll(
                    selection.elseBranch() == null
                        ? subject
                        : walk(selection.elseBranch().body(), subject)
                );
                return merge(changeExit(branches, Exit.YIELD, Exit.NORMAL));
            }
            case SwitchBranchBlockBody body:
                return walk(body.block(), paths);
            case SwitchBranchExpressionBody body:
                return walk(body.expression(), paths);
            case CallExpression call:
                return sequence(call.arguments(), walk(call.callee(), paths));
            case NewExpression creation:
                return sequence(creation.arguments(), paths);
            case NamedArgumentExpression argument:
                return walk(argument.value(), paths);
            case ArraySpread spread:
                return walk(spread.expression(), paths);
            case ArrayExpression array:
                return sequence(array.elements(), paths);
            case MapEntry entry:
                return walk(entry.value(), walk(entry.key(), paths));
            case MapSpread spread:
                return walk(spread.expression(), paths);
            case MapExpression map:
                return sequence(map.elements(), paths);
            case TupleExpression tuple:
                return sequence(tuple.elements(), paths);
            case SubscriptExpression subscript:
                return walk(subscript.index(), walk(subscript.target(), paths));
            case SliceExpression slice:
                return walk(
                    slice.endIndex(),
                    walk(slice.startIndex(), walk(slice.target(), paths))
                );
            case WhileStatement loop:
                return loop(path, loop.condition(), loop.body(), null, false);
            case DoWhileStatement loop:
                return loop(path, loop.condition(), loop.body(), null, true);
            case ForStatement loop: {
                final List<Path> result = new ArrayList<>();
                for (final Path initial : walk(loop.initializer(), paths)) {
                    if (initial.exit() != Exit.NORMAL) {
                        result.add(initial);
                    }
                    else {
                        result.addAll(
                            loop(
                                initial,
                                loop.condition(),
                                loop.body(),
                                loop.update(),
                                false
                            )
                        );
                    }
                }
                return merge(result);
            }
            case ForEachStatement loop: {
                final List<Path> result = new ArrayList<>();
                for (final Path initial : walk(loop.iterable(), paths)) {
                    if (initial.exit() != Exit.NORMAL) {
                        result.add(initial);
                    }
                    else {
                        result.addAll(
                            loop(initial, null, loop.body(), null, false, true)
                        );
                    }
                }
                return merge(result);
            }
            default:
                return paths;
        }
    }

    private List<Path> tryStatement(
        final TryStatement statement,
        final Path initial
    ) {
        final List<Path> outcomes;
        if (statement.finallyBody() != null) {
            pendingFinalizers++;
        }
        try {
            outcomes = tryBody(statement, initial);
        }
        finally {
            if (statement.finallyBody() != null) {
                pendingFinalizers--;
            }
        }
        if (statement.finallyBody() == null) {
            return merge(outcomes);
        }
        final List<Path> result = new ArrayList<>();
        for (final Path path : merge(outcomes)) {
            for (final Path finished : walk(
                statement.finallyBody(),
                List.of(path.withExit(Exit.NORMAL))
            )) {
                result.add(
                    finished.exit() == Exit.NORMAL
                        ? finished.withExit(path.exit())
                        : finished
                );
            }
        }
        return merge(result);
    }

    private List<Path> tryBody(
        final TryStatement statement,
        final Path initial
    ) {
        final List<Path> prefixes = new ArrayList<>();
        prefixes.add(initial);
        List<Path> body = List.of(initial);
        for (final BlockItem item : statement.body().items()) {
            body = walk(item, body);
            for (final Path path : body) {
                prefixes.add(path.withExit(Exit.NORMAL));
            }
        }
        final List<Path> outcomes = new ArrayList<>(body);
        final Path caught = normal(merge(prefixes));
        if (caught != null) {
            for (final CatchClause clause : statement.catches()) {
                outcomes.addAll(walk(clause.body(), List.of(caught)));
            }
        }
        return merge(outcomes);
    }

    private List<Path> loop(
        final Path initial,
        final @Nullable Expression condition,
        final Statement body,
        final @Nullable Expression update,
        final boolean doFirst
    ) {
        return loop(initial, condition, body, update, doFirst, false);
    }

    private List<Path> loop(
        final Path initial,
        final @Nullable Expression condition,
        final Statement body,
        final @Nullable Expression update,
        final boolean doFirst,
        final boolean foreach
    ) {
        final List<Path> exits = new ArrayList<>();
        List<Path> entry = List.of(initial);
        if (doFirst) {
            entry = loopBody(body, entry, update, exits);
        }
        Path head = normal(merge(entry));
        if (head == null) {
            return merge(exits);
        }
        final Path seed = head;
        while (true) {
            final List<Path> tested = walk(condition, List.of(head));
            final boolean alwaysTrue =
                !foreach && (condition == null || isBoolean(condition, "true"));
            final boolean alwaysFalse =
                condition != null && isBoolean(condition, "false");
            for (final Path testedPath : tested) {
                if (testedPath.exit() != Exit.NORMAL || !alwaysTrue) {
                    exits.add(testedPath);
                }
            }
            final List<Path> back =
                alwaysFalse ? List.of() : loopBody(body, tested, update, exits);
            final List<Path> heads = new ArrayList<>(back);
            heads.add(seed);
            final Path next = normal(merge(heads));
            if (next == null || next.equals(head)) {
                break;
            }
            head = next;
        }
        return merge(exits);
    }

    private List<Path> loopBody(
        final Statement body,
        final List<Path> entry,
        final @Nullable Expression update,
        final List<Path> exits
    ) {
        final List<Path> back = new ArrayList<>();
        for (final Path path : walk(body, entry)) {
            if (path.exit() == Exit.BREAK) {
                exits.add(path.withExit(Exit.NORMAL));
            }
            else if (
                path.exit() == Exit.NORMAL || path.exit() == Exit.CONTINUE
            ) {
                back.add(path.withExit(Exit.NORMAL));
            }
            else {
                exits.add(path);
            }
        }
        return walk(update, merge(back));
    }

    private static boolean isBoolean(
        final Expression expression,
        final String value
    ) {
        return unwrap(expression) instanceof LiteralExpression literal
            && literal.kind() == LiteralKind.BOOL
            && literal.text().equals(value);
    }

    private static @Nullable Path normal(final List<Path> paths) {
        return paths.stream()
            .filter(path -> path.exit() == Exit.NORMAL)
            .findFirst()
            .orElse(null);
    }

    private static List<Path> changeExit(
        final List<Path> paths,
        final Exit from,
        final Exit to
    ) {
        return paths.stream()
            .map(path -> path.exit() == from ? path.withExit(to) : path)
            .toList();
    }

    private static List<Path> merge(final List<Path> paths) {
        final EnumMap<Exit, Path> merged = new EnumMap<>(Exit.class);
        for (final Path path : paths) {
            final Path previous = merged.get(path.exit());
            if (previous == null) {
                merged.put(path.exit(), path);
            }
            else {
                final Set<Symbol> definite = new HashSet<>(previous.definite());
                final Set<Symbol> possible = new HashSet<>(previous.possible());
                definite.retainAll(path.definite());
                possible.addAll(path.possible());
                merged.put(
                    path.exit(),
                    new Path(
                        definite,
                        possible,
                        path.exit(),
                        previous.baseDefinite() && path.baseDefinite(),
                        previous.basePossible() || path.basePossible()
                    )
                );
            }
        }
        return new ArrayList<>(merged.values());
    }
}
