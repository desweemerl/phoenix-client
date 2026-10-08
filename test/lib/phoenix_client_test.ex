defmodule PhoenixClientTest do
  use Application

  def start(_type, _args) do
    children = [
      {Phoenix.PubSub, name: PhoenixClientTest.PubSub},
      PhoenixClientTestWeb.Endpoint
    ]

    opts = [
      strategy: :one_for_one,
      name: PhoenixClientTest.Supervisor
    ]

    Supervisor.start_link(children, opts)
  end
end
