/**
 * Copyright (c) 2014-2014 openHAB UG (haftungsbeschraenkt) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.max.internal.exceptions;

/**
 * Will be thrown when there is an attempt to put a new message line into the message processor,
 * but the processor is not yet ready to handle new lines because there is already a message that
 * has be pulled before.
 * 
 * @author Christian Rockrohr <christian@rockrohr.de>
 */
public class MessageIsWaitingException extends Exception {

}
