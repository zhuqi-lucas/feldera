"""Run multiple Python tests in a single pipeline"""

from typing import TypeAlias
from types import ModuleType
import os
import re
import inspect
import importlib
import sys

from feldera import PipelineBuilder, Pipeline
from feldera.enums import CompilationProfile
from tests import TEST_CLIENT

JSON: TypeAlias = dict[str, "JSON"] | list["JSON"] | str | int | float | bool | None

# pylint: disable=too-many-function-args,missing-function-docstring,no-self-argument,no-self-use,invalid-name,line-too-long,too-few-public-methods,missing-class-docstring,super-init-not-called

DEBUG=False

def beautify(sql: str) -> str:
    body = sql.split('\n')
    body = [x.strip() for x in body]
    return "\n".join(body)

class SqlObject:
    """Base class for tables and views
       Each sql object has a name, a definition in SQL, and some data"""
    def __init__(self, name: str, sql:str, data: JSON):
        """Create a SQL object"""
        self.name = name
        self.sql = beautify(sql)
        self.data = data

    def get_sql(self) -> str:
        """Get the SQL defining the object"""
        return self.sql

    def get_data(self) -> JSON:
        """Get the data that is the object's contents"""
        return self.data

    @staticmethod
    def extract_name(sql: str, table: bool) -> str:
        """Extract a table or view name from a SQL string;
           assumes a nicely written SQL program"""

        pattern = r'CREATE\s+' + ('TABLE' if table else 'VIEW') + r'\s+(\w+)'
        match = re.search(pattern, sql, re.IGNORECASE)
        # If a match is found, return the table name
        if match:
            return match.group(1)
        raise "Could not parse sql " + sql


class Table(SqlObject):
    """A SQL table with contents"""
    @staticmethod
    def add_insert(data: JSON) -> JSON:
        return [{"insert": x} for x in data]

    def __init__(self, sql: str, data: JSON):
        super().__init__(SqlObject.extract_name(sql, True), sql, Table.add_insert(data))


class View(SqlObject):
    """A SQL view with contents"""
    def __init__(self, sql: str, data: JSON):
        super().__init__(SqlObject.extract_name(sql, False), sql, data)
        self.listener = None

    def listen(self, pipeline: Pipeline):
        """Listen to an output handler

        arguments:
        pipeline -- pipeline to listen to"""
        self.listener = pipeline.listen(self.name)

    def validate(self):
        """Check that the data received matches the expected data"""
        data = self.listener.to_dict()
        expected = self.get_data()
        assert expected == data , f"ASSERTION ERROR: failed view:{self.name}: {expected} != {data}"


class TestAccumulator:
    """Base class which accumulates multiple DBSP tests to run and executes them"""
    def __init__(self):
        self.tables = []
        self.views = []

    def add_table(self, table: Table):
        """Add a new table to the program"""
        if DEBUG:
            print(f"Adding table {table.name}")
        self.tables.append(table)

    def add_view(self, view: View):
        """Add a new view to the program"""
        if DEBUG:
            print(f"Adding view {view.name}")
        self.views.append(view)

    def generate_sql(self) -> str:
        """Generate SQL for the program"""
        result = ""
        for table in self.tables:
            if DEBUG:
                print(table.name, table.sql)
            result += (table.get_sql() + ";\n\n")
        result += "\n"
        for view in self.views:
            result += (view.get_sql() + ";\n\n")
        if DEBUG:
            print("Generated sql\n" + result)
        return result

    def run_tests(self):
        """Run all tests registered"""
        sql = self.generate_sql()
        pipeline = PipelineBuilder(
            TEST_CLIENT,
            "test",
            sql=sql,
            compilation_profile=CompilationProfile.DEV).create_or_replace()

        for view in self.views:
            view.listen(pipeline)

        pipeline.start()

        for table in self.tables:
            data = table.get_data()
            pipeline.input_json(table.name, data, update_format="insert_delete")

        pipeline.wait_for_completion(shutdown=False)
        for view in self.views:
            view.validate()

        pipeline.shutdown()


class TestTable:
    """Base class for defining tables"""
    def __init__(self):
        self.sql = ""
        self.data = []

    def register(self, ta: TestAccumulator):
        ta.add_table(Table(self.sql, self.data))


class TestView:
    """Base class for defining views"""
    def __init__(self):
        self.sql = ""
        self.data = []

    def register(self, ta: TestAccumulator):
        ta.add_view(View(self.sql, self.data))


######################
## Add here import statements for all files with tests

from tests.aggregate_tests import test_decimal_avg, test_decimal_agg

def register_tests_in_module(module, ta: TestAccumulator):
    """Registers all the tests in the specified module.
       Tests are classes that start with test_.
       They must all derive from TestView or TestTable"""
    for name, obj in inspect.getmembers(module):
        if name.startswith("test_"):
            if inspect.isclass(obj):
                cls = getattr(module, name)
                instance = cls()
                instance.register(ta)
                if DEBUG:
                    print(f"Registering {name}")

def main():
    """Find all tests loaded by the current module and register them"""
    ta = TestAccumulator()
    current_module = sys.modules[__name__]
    loaded = []
    for key in current_module.__dict__.keys():
        module = current_module.__dict__[key]
        if isinstance(module, ModuleType):
            if not module.__name__.startswith("tests.aggregate_tests"):
                continue
            loaded.append(module);
    for module in loaded:
        register_tests_in_module(module, ta)
    ta.run_tests()

if __name__ == '__main__':
    main()
