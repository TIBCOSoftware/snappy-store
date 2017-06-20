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
package com.newedge.staticdata.domain;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

/**
 * Product from NPR.
 * 
 * @author pierre.tamisier
 * @version $Id: Product.java 5418 2011-09-05 13:45:46Z niall.pemberton $
 */
public class Product implements Serializable {

    public static final long serialVersionUID = 1L;

    public String instrumentId;
    public String instrumentAliasId;
    public String sourceExchangeCode;
    public String micExchangeCode;
    public String exchangeCode;
    public String exchangeGroup;
    public String productCode;
    public String contractType;
    public String sourceSystem;
    public String currencyCode;
    public Date modifiedDate;
    public float tradePriceMultiplier;
    public float contractSizeMultiplier;
    public float premiumMultiplier;
    public String subExchangeCode;
    public String futuresCode;
    public String securitySubTypeCode;
    public String countryCode;
    public String cusip;
    public String productType;
    public String additionalSecurityId;

}
