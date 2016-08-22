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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * BackOfficeAccount. This will represent both the GMI and UBIX accounts
 * 
 * @author nileesha.bojjawar
 * @version $Id: BackOfficeAccount.java 5529 2011-09-13 02:07:37Z niall.pemberton $
 */
public class BackOfficeAccount implements Serializable {

    public static final long serialVersionUID = 1L;
    public String accountCode;
    public String location;
    public BackOfficeAccountSystem system;
    public String firm;
    public String office;
    public String account;
    public String environment;

}
