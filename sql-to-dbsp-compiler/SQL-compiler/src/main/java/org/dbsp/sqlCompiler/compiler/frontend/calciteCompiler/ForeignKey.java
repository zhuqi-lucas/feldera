package org.dbsp.sqlCompiler.compiler.frontend.calciteCompiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dbsp.sqlCompiler.compiler.errors.SourcePositionRange;
import org.dbsp.sqlCompiler.compiler.frontend.parser.SqlFragment;
import org.dbsp.sqlCompiler.compiler.frontend.parser.SqlFragmentIdentifier;
import org.dbsp.util.Utilities;

import java.util.List;

/** Canonical representation of a foreign key from a table.
 * A table can have many foreign keys.
 * Each foreign key maps a set of columns from the table
 * to a set of columns from another table. */
public class ForeignKey {
    public ForeignKey(TableAndColumns thisTable, TableAndColumns otherTable) {
        this.thisTable = thisTable;
        this.otherTable = otherTable;
    }

    /** Represents a table and a list of columns. */
    public static class TableAndColumns {
        // Position of the whole list of columns in source code
        public final SourcePositionRange listPos;
        public final SqlFragmentIdentifier tableName;
        public final List<SqlFragmentIdentifier> columnNames;

        TableAndColumns(SourcePositionRange listPos, SqlFragmentIdentifier tableName,
                        List<SqlFragmentIdentifier> columnNames) {
            this.listPos = listPos;
            this.tableName = tableName;
            this.columnNames = columnNames;
        }
    }

    public final TableAndColumns thisTable;
    public final TableAndColumns otherTable;

    public JsonNode asJson() {
        ObjectMapper mapper = Utilities.deterministicObjectMapper();
        ObjectNode result = mapper.createObjectNode();
        ArrayNode columns = result.putArray("columns");
        for (SqlFragment column: this.thisTable.columnNames)
            columns.add(column.getString());
        result.put("refers", this.otherTable.tableName.toString());
        ArrayNode tocolumns = result.putArray("tocolumns");
        for (SqlFragment column: this.otherTable.columnNames)
            tocolumns.add(column.getString());
        return result;
    }
}
