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
import coupledL2.tl2chi.CHISNP

// receive inner task and send Snoop upwards
class TXSNP (implicit p: Parameters) extends LLCModule {
  val io = IO(new Bundle() {
    val task = Flipped(DecoupledIO(new Task()))
    val snp = DecoupledIO(new CHISNP())
    val snpMask = Output(Vec(numRNs, Bool()))
  })

  io.snp.valid := io.task.valid
  io.snp.bits := io.task.bits.toCHISNPBundle()
  io.snpMask := io.task.bits.snpVec

  io.task.ready := io.snp.ready
}
