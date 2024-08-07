/** *************************************************************************************
 * Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
 * Copyright (c) 2020-2021 Peng Cheng Laboratory
 *
 * XiangShan is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 * http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 * *************************************************************************************
 */

package openLLC

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import coupledL2.tl2chi.CHIDAT

class RXDAT (implicit p: Parameters) extends LLCModule {
  val io = IO(new Bundle() {
    val in  = Flipped(DecoupledIO(new CHIDAT()))
    val out = ValidIO(new RespWithData())
  })

  io.out.valid := io.in.valid
  io.in.ready := true.B

  def fromCHIRSPtoRespWithData(r: CHIDAT): RespWithData = {
    val rsp = Wire(new RespWithData())
    rsp.txnID := r.txnID
    rsp.dbID := r.dbID
    rsp.opcode := r.opcode
    rsp.resp := r.resp
    rsp.srcID := r.srcID
    rsp.data.data := r.data
    rsp.dataID := r.dataID
    rsp
  }
  io.out.bits := fromCHIRSPtoRespWithData(io.in.bits)

}
