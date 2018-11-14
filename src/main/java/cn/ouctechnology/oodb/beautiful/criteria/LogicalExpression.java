package cn.ouctechnology.oodb.beautiful.criteria;

/**
 * @program: oodb
 * @author: ZQX
 * @create: 2018-11-13 09:53
 * @description: TODO
 **/
public class LogicalExpression implements Criterion {
    private final Criterion lhs;
    private final Criterion rhs;
    private final String op;

    LogicalExpression(Criterion lhs, Criterion rhs, String op) {
        this.lhs = lhs;
        this.rhs = rhs;
        this.op = op;
    }

    @Override
    public String toOqlString(String tableName) {
        return '('
                + lhs.toOqlString(tableName)
                + ' '
                + getOp()
                + ' '
                + rhs.toOqlString(tableName)
                + ')';
    }

    public String getOp() {
        return op;
    }

    @Override
    public String toString() {
        return lhs.toString() + ' ' + getOp() + ' ' + rhs.toString();
    }
}
