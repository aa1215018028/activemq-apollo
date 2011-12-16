/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.apollo.broker.protocol

import org.fusesource.hawtdispatch.transport.Transport
import java.util.concurrent.TimeUnit

/**
 * <p>A HeartBeatMonitor can be used to periodically check the activity
 * of a transport to see if it is still alive or if a keep alive
 * packet needs to be transmitted to keep it alive.</p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class HeartBeatMonitor() {

  var transport:Transport = _
  var initial_write_check_delay = 0L
  var initial_read_check_delay = 0L
  var write_interval = 0L
  var read_interval = 0L

  var on_keep_alive = ()=>{}
  var on_dead = ()=>{}

  var session = 0
  
  def schedule(session:Int, interval:Long, func:() => Unit) = {
    if ( this.session==session ) {
      transport.getDispatchQueue.after(interval, TimeUnit.MILLISECONDS) {
        if ( this.session==session ) {
          func()
        }
      }
    }
  }

  def schedual_check_writes(session:Int):Unit = {
    var func = () => {
      schedual_check_writes(session)
    }
    
    Option(transport.getProtocolCodec).foreach { codec=>
      val last_write_counter = codec.getWriteCounter()
      func = () => {
        if( last_write_counter==codec.getWriteCounter ) {
          on_keep_alive()
        }
        schedual_check_writes(session)
      }
    }
    schedule(session, write_interval/2, func)
  }

  def schedual_check_reads(session:Int):Unit = {
    var func = () => {
      schedual_check_reads(session)
    }
    Option(transport.getProtocolCodec).foreach { codec=>
      val last_read_counter = codec.getReadCounter
      func = () => {
        if( last_read_counter==codec.getReadCounter ) {
          on_dead()
        }
        schedual_check_reads(session)
      }
    }
    schedule(session, read_interval, func)
  }

  def start = {
    session += 1
    if( write_interval!=0 ) {
      if ( initial_write_check_delay!=0 ) {
        transport.getDispatchQueue.after(initial_write_check_delay, TimeUnit.MILLISECONDS) {
          schedual_check_writes(session)
        }
      } else {
        schedual_check_writes(session)
      }
    }
    if( read_interval!=0 ) {
      if ( initial_read_check_delay!=0 ) {
        transport.getDispatchQueue.after(initial_read_check_delay, TimeUnit.MILLISECONDS) {
          schedual_check_reads(session)
        }
      } else {
        schedual_check_reads(session)
      }
    }
  }

  def stop = {
    session += 1
  }
}