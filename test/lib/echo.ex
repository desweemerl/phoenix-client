defmodule PhoenixClientTestWeb.Echo do
  @moduledoc """
  Answers HTTP requests with what was received, to test the client's HTTP requests.
  `/status/<code>` answers with that status.
  """
  import Plug.Conn

  def init(opts), do: opts

  def call(conn, _opts) do
    {:ok, body, conn} = read_body(conn)
    conn = fetch_query_params(conn)

    status =
      case conn.path_info do
        ["status", code] -> String.to_integer(code)
        _ -> 200
      end

    echo = %{
      method: conn.method,
      path: conn.request_path,
      query: conn.query_params,
      content_type: conn |> get_req_header("content-type") |> List.first(),
      token: conn |> get_req_header("x-token") |> List.first(),
      body: body
    }

    conn
    |> put_resp_content_type("application/json")
    |> send_resp(status, Jason.encode!(echo))
  end
end
