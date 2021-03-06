require_relative '../../spec_helper'

describe "Proc#hash" do
  it "is provided" do
    proc {}.respond_to?(:hash).should be_true
    -> {}.respond_to?(:hash).should be_true
  end

  it "returns an Integer" do
    proc { 1 + 489 }.hash.should be_kind_of(Fixnum)
  end

  it "is stable" do
    body = proc { :foo }
    proc(&body).hash.should == proc(&body).hash
  end
end
