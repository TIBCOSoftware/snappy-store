APP.OLTP_PNP_Subscriptions.ID = ::UUID
APP.OLTP_PNP_Subscriptions.MSISDN = ::valuelist bashexecute [  echo {a..n}{Q..Z}, | sed 's/ //g' ]
APP.OLTP_PNP_Subscriptions.WALLETNAME = ::valuelist bashexecute [  echo {a..u}{A..Z}{A..X}{a..c}, | sed 's/ //g' ]
APP.OLTP_PNP_Subscriptions.BRAND = ::valuelist bashexecute [  echo {A..N}{Q..T}, | sed 's/ //g' ]


#::foreach SEC_OWNER.SECT_CHANNEL_DATA.CHANNEL_NAME repeat 3 
#SEC_OWNER.SECT_CHANNEL_DATA.CLIENT_ID = 0,1
#SEC_OWNER.SECT_CHANNEL_DATA.CLIENT_NAME = client,server
#::hcaerof SEC_OWNER.SECT_CHANNEL_DATA.CHANNEL_NAME
