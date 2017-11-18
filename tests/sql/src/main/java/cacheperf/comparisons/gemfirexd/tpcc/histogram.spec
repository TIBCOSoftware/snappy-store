//------------------------------------------------------------------------------
// op response time histogram
//------------------------------------------------------------------------------

statspec opsTotal *client* perffmwk.HistogramStats transactions* operations
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;

statspec opsTotal_00000_ms_00001_ms *client* perffmwk.HistogramStats transactions* opsLessThan1000000ns
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec opsTotal_00001_ms_00002_ms *client* perffmwk.HistogramStats transactions* opsLessThan2000000ns
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec opsTotal_00002_ms_00005_ms *client* perffmwk.HistogramStats transactions* opsLessThan5000000ns
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec opsTotal_00005_ms_00010_ms *client* perffmwk.HistogramStats transactions* opsLessThan10000000ns
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec opsTotal_00010_ms_00100_ms *client* perffmwk.HistogramStats transactions* opsLessThan100000000ns
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec opsTotal_00100_ms_01000_ms *client* perffmwk.HistogramStats transactions* opsLessThan1000000000ns
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec opsTotal_01000_ms_10000_ms *client* perffmwk.HistogramStats transactions* opsLessThan10000000000ns
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;
statspec opsTotal_10000_ms_and_over *client* perffmwk.HistogramStats transactions* opsMoreThan10000000000ns
filter=none combine=combineAcrossArchives ops=max-min! trimspec=transactions
;

expr ops_00000_ms_00001_ms = opsTotal_00000_ms_00001_ms / opsTotal ops=max-min?
;
expr ops_00001_ms_00002_ms = opsTotal_00001_ms_00002_ms / opsTotal ops=max-min?
;
expr ops_00002_ms_00005_ms = opsTotal_00002_ms_00005_ms / opsTotal ops=max-min?
;
expr ops_00005_ms_00010_ms = opsTotal_00005_ms_00010_ms / opsTotal ops=max-min?
;
expr ops_00010_ms_00100_ms = opsTotal_00010_ms_00100_ms / opsTotal ops=max-min?
;
expr ops_00100_ms_01000_ms = opsTotal_00100_ms_01000_ms / opsTotal ops=max-min?
;
expr ops_01000_ms_10000_ms = opsTotal_01000_ms_10000_ms / opsTotal ops=max-min?
;
expr ops_10001_ms_and_over = opsTotal_10000_ms_and_over / opsTotal ops=max-min?
;
