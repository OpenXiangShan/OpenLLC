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
import utility._
import coupledL2.tl2chi._
import org.chipsalliance.cde.config.Parameters

class AtomicsMainPipe(implicit p: Parameters) extends LLCBundle {
  val task = new Task()
  val dataisfromDS = Bool()
  val datafromDS = new DSBlock()
}

class AMOALU(implicit p: Parameters) extends LLCModule with HasCHIOpcodes {
  val io = IO(new Bundle {
    val opt = Input(UInt(REQ_OPCODE_WIDTH.W))
    val out = Output(UInt(XLEN.W))
    val data1 = Input(UInt(XLEN.W))
    val data2 = Input(UInt(XLEN.W))
  })

  io.out := ParallelLookUp(
    io.opt,
    Seq(
      AtomicLoad_ADD -> (io.data1 + io.data2),
      AtomicLoad_CLR -> (~io.data1 & io.data2),
      AtomicLoad_EOR -> (io.data1 ^ io.data2),
      AtomicLoad_SET -> (io.data1 | io.data2),
      AtomicLoad_SMAX -> Mux(io.data1.asSInt > io.data2.asSInt, io.data1, io.data2),
      AtomicLoad_SMIN -> Mux(io.data1.asSInt < io.data2.asSInt, io.data1, io.data2),
      AtomicLoad_UMAX -> Mux(io.data1 > io.data2, io.data1, io.data2),
      AtomicLoad_UMIN -> Mux(io.data1 < io.data2, io.data1, io.data2),
      AtomicSwap -> io.data1
    )
  )
}

class AtomicsUnitL3(implicit p: Parameters) extends LLCModule {
  val io = IO(new Bundle() {
    val fromMainPipe = Flipped(ValidIO(new AtomicsMainPipe()))
    val fromResponseUnit = Flipped(ValidIO(new AtomicsInfo()))

    val AMOrefillTask = DecoupledIO(new Task())
    val datafromAMO = Output(UInt(XLEN.W))
    val blockfromAMO = Output(new DSBlock())

    val blockReqArb = Output(Bool())
  })

  // only support 32bits and 64bits
  def block2Nbit(data_block: UInt, offset: UInt, size: UInt): UInt = {
    val N = Mux(size === 2.U, 32.U, 64.U)
    val data_ret = (data_block >> (offset * 8.U)).asUInt
    assert((N === 32.U || N === 64.U), "amo data length not support")
    assert((offset % (N / 8.U).asUInt) === 0.U, "amo_data not alias")

    Mux(N === 64.U, data_ret(63, 0), Fill(2, data_ret(31, 0)))
  }

  def Nbit2block(data_block: UInt, offset: UInt, new_data: UInt, size: UInt): UInt = {
    val N = Mux(size === 2.U, 32.U, 64.U)
    val OFFSET = (offset * 8.U)
    val data = Mux(N === 64.U, new_data(63, 0), new_data(31, 0))
    assert((N === 32.U || N === 64.U), "amo data length not support")
    assert((offset % (N / 8.U).asUInt) === 0.U, "amo_data not alias")

    (((((data_block >> (OFFSET + N))) << N) | data) << OFFSET) | (data_block & ((1.U << OFFSET) - 1.U))
  }

  val amoalu = Module(new AMOALU)
  val s_invalid :: s_getMainPipe :: s_getResponseUnit :: Nil = Enum(3)
  val state = RegInit(s_invalid)
  val dataisfromDS = RegInit(false.B)
  val datafromDS = RegInit(0.U((blockBytes * 8).W))
  val datafromL2 = RegInit(0.U(XLEN.W))
  val datafromSNPorMEM = RegInit(0.U((blockBytes * 8).W))
  val Result = RegInit(0.U(XLEN.W))
  val offset = RegInit(0.U(offsetBits.W))
  val size = RegInit(0.U(SIZE_WIDTH.W))
  val Task = RegInit(0.U.asTypeOf(new Task()))

  when (state === s_invalid) {
    when (io.fromMainPipe.valid) {
      dataisfromDS := io.fromMainPipe.bits.dataisfromDS
      datafromDS := io.fromMainPipe.bits.datafromDS.data.asUInt
      offset := io.fromMainPipe.bits.task.off
      size := io.fromMainPipe.bits.task.size
      Task := io.fromMainPipe.bits.task
      state := s_getMainPipe
    }
  }

  when (state === s_getMainPipe) {
    when (io.fromResponseUnit.valid) {
      datafromL2 := io.fromResponseUnit.bits.ncbwrdata
      datafromSNPorMEM := io.fromResponseUnit.bits.old_data.asUInt
      state := s_getResponseUnit
    }
  }

  when (state === s_getResponseUnit) {
    Result := amoalu.io.out
    when (io.AMOrefillTask.fire) {
      state := s_invalid
    }
  }

  val old_data_block = Mux(dataisfromDS, datafromDS, datafromSNPorMEM)
  amoalu.io.data1 := datafromL2
  amoalu.io.data2 := block2Nbit(old_data_block, offset, size)
  amoalu.io.opt := Task.chiOpcode

  io.AMOrefillTask.valid := (state === s_getResponseUnit)
  io.AMOrefillTask.bits := Task
  io.AMOrefillTask.bits.AMOrefillTask := true.B

  io.datafromAMO := block2Nbit(old_data_block, offset, size)
  io.blockfromAMO := Nbit2block(old_data_block, offset, Result, size).asTypeOf(new DSBlock())
  io.blockReqArb := !(state === s_invalid)
}