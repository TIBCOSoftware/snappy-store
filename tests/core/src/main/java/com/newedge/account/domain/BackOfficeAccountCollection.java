/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package com.newedge.account.domain;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * BackOfficeAccountCollection.
 * 
 * @author nileesha.bojjawar
 * @version $Id: BackOfficeAccountCollection.java 5533 2011-09-13 17:15:59Z omprakash.kuppurajah $
 */
public class BackOfficeAccountCollection implements Serializable {

    public static final long serialVersionUID = 1L;

    public String accountCollectionCode;

    public String description;

    public String clearingInfo;

    public String exchangeData;

    public String exchangeGroup;

    public String ncAccount;

    public boolean giveUpAccountFlag;

    public String xgmMarket;

    /**
     * comma separated back office account list TODO should this be just a list than a comma separated string?
     */
    public List<String> backOfficeAccountCodesList;

    public List<AccountCode> backOfficeAccountCodes;

}
