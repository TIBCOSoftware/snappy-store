//------------------------------------------------------------------------------
// server startup speeds
//------------------------------------------------------------------------------

statspec serverStartupTime *client* TPCCStats * serverStartupTime
filter=none combine=combineAcrossArchives ops=max-min? trimspec=none
;
