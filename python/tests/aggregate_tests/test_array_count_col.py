from .aggtst_base import TstView


class aggtst_array_count_col(TstView):
    def __init__(self):
        # Validated on Postgres
        self.data = [{"c1": 4, "c2": 3, "c3": 3}]
        self.sql = """CREATE MATERIALIZED VIEW array_count_col AS SELECT
                      COUNT(c1) AS c1, COUNT(c2) AS c2, COUNT(c3) AS c3
                      FROM array_tbl"""


class aggtst_array_count_col_gby(TstView):
    def __init__(self):
        # Validated on Postgres
        self.data = [{"id": 0, "c1": 2, "c2": 1, "c3": 2}, {"id": 1, "c1": 2, "c2": 2, "c3": 1}]
        self.sql = """CREATE MATERIALIZED VIEW array_count_col_gby AS SELECT
                      id, COUNT(c1) AS c1, COUNT(c2) AS c2, COUNT(c3) AS c3
                      FROM array_tbl
                      GROUP BY id"""


class aggtst_array_count_col_distinct(TstView):
    def __init__(self):
        # Validated on Postgres
        self.data = [{"c1": 3, "c2": 3, "c3": 3}]
        self.sql = """CREATE MATERIALIZED VIEW array_count_col_distinct AS SELECT
                      COUNT(DISTINCT c1) AS c1, COUNT(DISTINCT c2) AS c2, COUNT(DISTINCT c3) AS c3
                      FROM array_tbl"""


class aggtst_array_count_col_distinct_gby(TstView):
    def __init__(self):
        # Validated on Postgres
        self.data = [{"id": 0, "c1": 2, "c2": 1, "c3": 2}, {"id": 1, "c1": 2, "c2": 2, "c3": 1}]
        self.sql = """CREATE MATERIALIZED VIEW array_count_col_distinct_gby AS SELECT
                      id, COUNT(DISTINCT c1) AS c1, COUNT(DISTINCT c2) AS c2, COUNT(DISTINCT c3) AS c3
                      FROM array_tbl
                      GROUP BY id"""


class aggtst_array_count_col_where(TstView):
    def __init__(self):
        # Validated on Postgres
        self.data = [{"c1": 2, "c2": 2, "c3": 1}]
        self.sql = """CREATE MATERIALIZED VIEW array_count_col_where AS SELECT
                      COUNT(c1) FILTER(WHERE c1 < c2) AS c1, COUNT(c2) FILTER(WHERE c1 < c2) AS c2, COUNT(c3) FILTER(WHERE c1 < c2) AS c3
                      FROM array_tbl"""


class aggtst_array_count_where_groupby(TstView):
    def __init__(self):
        # Validated on Postgres
        self.data = [{"id": 0, "c1": 1, "c2": 1, "c3": 1}, {"id": 1, "c1": 1, "c2": 1, "c3": 0}]
        self.sql = """CREATE MATERIALIZED VIEW array_count_col_where_gby AS SELECT
                      id, COUNT(c1) FILTER(WHERE c1 < c2) AS c1, COUNT(c2) FILTER(WHERE c1 < c2) AS c2, COUNT(c3) FILTER(WHERE c1 < c2) AS c3
                      FROM array_tbl
                      GROUP BY id"""
